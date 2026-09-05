package com.paymesh.shared.tenant;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The {@code merchant_ref} projection: the read model that replaced the {@code * -> merchants}
 * foreign key (ADR-039). One class plays both sides of the projection -- it {@link #upsert}s the
 * copies (called by {@link MerchantRefProjector} on each merchant lifecycle event) and it answers
 * the platform's gate ({@link #verdict}, implementing {@link MerchantStatusGate}).
 *
 * <h2>WHY RAW SQL AND NOT A JPA ENTITY</h2>
 *
 * The projection lives in <em>six</em> schemas under the same name, {@code merchant_ref}. That
 * breaks the one assumption that lets this codebase map tables with a bare {@code @Table(name=...)}
 * and resolve them by {@code search_path}: globally-unique names (ADR-038 section 6). A bare entity
 * would bind to whichever copy {@code search_path} hit first, and {@code ddl-auto=validate} would
 * check only that one. So the projection is reached by schema-qualified SQL instead -- the first
 * {@link JdbcTemplate} use in the codebase, but it is Spring-core, already on the classpath, and six
 * near-identical entities to avoid it would be exactly the boilerplate a three-column cache makes
 * absurd.
 *
 * <h2>ONE PROCESS WRITES EVERY COPY</h2>
 *
 * In the monolith one projector feeds all copies so each is identical and every service's schema is
 * warm for extraction. It runs inside the dispatcher's transaction (it must not open its own -- the
 * {@link com.paymesh.shared.outbox.application.EventHandler} rule), so all copies commit together
 * with the inbox row.
 */
public final class MerchantRefStore implements MerchantStatusGate {

    /**
     * The service schemas that carry a {@code merchant_ref} copy (V39). Every real service that held
     * the {@code * -> merchants} FK; {@code platform} is excluded because it splits per-service at
     * extraction and a single copy there could not lift.
     * <p>
     * ponytail: fan-out write to six schema copies; at extraction each service keeps only its own,
     * fed from Kafka, and this list collapses to one. The names are a fixed constant, never input,
     * so interpolating them into SQL carries no injection risk.
     */
    private static final List<String> SCHEMAS =
        List.of("payment", "ledger", "settlement", "risk", "webhook", "engagement");

    private final JdbcTemplate jdbc;

    public MerchantRefStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Writes the merchant's status into every schema copy.
     * <p>
     * The {@code WHERE EXCLUDED.updated_at >= merchant_ref.updated_at} guard drops an out-of-order
     * (older) event so a stale status can never overwrite a newer one -- at-least-once delivery can
     * reorder two <em>different</em> events for one merchant even though the inbox dedups the same
     * one.
     */
    public void upsert(MerchantId merchantId, String status, Instant occurredAt) {
        Timestamp updatedAt = Timestamp.from(occurredAt);

        for (String schema : SCHEMAS) {
            jdbc.update(
                "INSERT INTO " + schema + ".merchant_ref (merchant_id, status, updated_at) "
                    + "VALUES (?, ?, ?) "
                    + "ON CONFLICT (merchant_id) DO UPDATE SET "
                    + "status = EXCLUDED.status, updated_at = EXCLUDED.updated_at "
                    + "WHERE EXCLUDED.updated_at >= merchant_ref.updated_at",
                merchantId.value(), status, updatedAt
            );
        }
    }

    /**
     * Reads any copy through {@code search_path} -- unqualified, because every copy is identical by
     * construction, so the one {@code search_path} resolves is authoritative-equivalent.
     */
    @Override
    public MerchantTransactability verdict(MerchantId merchantId) {
        List<String> statuses = jdbc.queryForList(
            "SELECT status FROM merchant_ref WHERE merchant_id = ?",
            String.class, merchantId.value()
        );

        if (statuses.isEmpty()) {
            return MerchantTransactability.UNKNOWN;
        }

        return "ACTIVE".equals(statuses.get(0))
            ? MerchantTransactability.ALLOWED
            : MerchantTransactability.DENIED;
    }
}
