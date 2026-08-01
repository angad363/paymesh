package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Persistence model for the outbox_events table (V7__create_outbox_events.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 * <p>
 * {@code @Immutable} because the table is append-only: an event is a record of something that
 * already happened, so there is no legitimate UPDATE to it. Hibernate takes it literally and drops
 * the row from dirty checking entirely, which is both the correct semantics and, here, the fix for
 * a measured bug -- the {@code payload} map below snapshots through serialize/deserialize, so
 * {@code amountMinor} goes in as a Long and comes back an Integer, the entity looks dirty on every
 * flush, and each append cost an INSERT plus two pointless UPDATEs.
 * <p>
 * Watch what that leaves: Spring Data sees a non-null application-minted id and no {@code @Version},
 * so {@code save} merges rather than persists -- a SELECT, then an INSERT. A reused event_id would
 * therefore not collide on the primary key. {@code @Immutable} makes that a silent no-op rather than
 * an overwrite of an event a consumer is about to dedup against, which is the outcome worth having.
 * (event_id is a random UUID; this is a correctness argument, not a live risk.)
 * <p>
 * {@code published_at} is deliberately unmapped. There is no relay, so nothing in this codebase
 * reads or writes it; the column exists so the relay needs no migration, and mapping a field no
 * code touches would only invite someone to set it.
 */
@Entity
@Immutable
@Table(name = "outbox_events")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "event_id", nullable = false, length = 40)
    private String eventId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 40)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    // JSONB. Hibernate serializes the map with the application's JSON mapper, so a value that
    // cannot round-trip fails on write rather than on some later read -- and, more to the point,
    // rather than on a consumer that cannot be told to try again.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Required by JPA. Not for application use. */
    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(
        String eventId,
        String merchantId,
        String aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        Map<String, Object> payload,
        Instant occurredAt
    ) {
        this.eventId = eventId;
        this.merchantId = merchantId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }
}
