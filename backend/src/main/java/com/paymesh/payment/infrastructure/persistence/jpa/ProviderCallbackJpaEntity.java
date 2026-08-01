package com.paymesh.payment.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Map;

/**
 * Persistence model for the provider_callbacks table (V10__create_provider_callbacks.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 * <p>
 * <b>{@code Persistable.isNew()} ALWAYS ANSWERS TRUE, AND THAT IS THE POINT OF THIS CLASS.</b>
 * <p>
 * Spring Data decides between {@code persist} and {@code merge} by asking whether the entity is new,
 * and its default answer for an application-assigned id with no {@code @Version} is "no" -- so
 * {@code save} would MERGE: a SELECT, then an INSERT or an UPDATE. A duplicate delivery would find
 * the existing row and quietly UPDATE it, the primary key would never be violated, and the entire
 * deduplication mechanism of ADR-012 would be a no-op that still returned APPLIED. Answering true
 * forces {@code persist}, which always issues an INSERT and therefore always collides.
 * <p>
 * The outbox reached the same fork and took the other branch: {@code @Immutable}, so a re-append is
 * a silent no-op. That is right there -- a re-appended event must not overwrite one a consumer may
 * have read -- and wrong here, because this insert's collision is the signal the caller acts on.
 */
@Entity
@Table(name = "provider_callbacks")
@IdClass(ProviderCallbackJpaId.class)
public class ProviderCallbackJpaEntity implements Persistable<ProviderCallbackJpaId> {

    @Id
    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Id
    @Column(name = "external_event_id", nullable = false, length = 120)
    private String externalEventId;

    // DERIVED from the intent the callback named, never supplied by the caller.
    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "payment_intent_id", nullable = false, length = 40)
    private String paymentIntentId;

    // CHAR(64) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    // JSONB, REDACTED BY ALLOWLIST before it gets here (SDD 12.6). Only the fields ProviderEvent can
    // hold survive, so a provider that starts sending instrument data has nowhere to put it.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    // Enum NAME, never the ordinal. Never DUPLICATE -- ck_provider_callbacks_outcome agrees.
    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    // The PROVIDER's clock, which is what the ordering guard compares. Not received_at.
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** Required by JPA. Not for application use. */
    protected ProviderCallbackJpaEntity() {
    }

    public ProviderCallbackJpaEntity(
        String provider,
        String externalEventId,
        String merchantId,
        String paymentIntentId,
        String payloadHash,
        Map<String, Object> payload,
        String outcome,
        Instant occurredAt,
        Instant receivedAt,
        Instant processedAt
    ) {
        this.provider = provider;
        this.externalEventId = externalEventId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.payloadHash = payloadHash;
        this.payload = payload;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.processedAt = processedAt;
    }

    @Override
    public ProviderCallbackJpaId getId() {
        return new ProviderCallbackJpaId(provider, externalEventId);
    }

    /**
     * ALWAYS TRUE. Nothing in this codebase updates a callback row -- there is no path that reads one
     * back and saves it -- so "new" is the only state this entity is ever saved in, and the answer
     * that forces an INSERT is also the honest one. See the class comment for what a merge would cost.
     */
    @Override
    public boolean isNew() {
        return true;
    }
}
