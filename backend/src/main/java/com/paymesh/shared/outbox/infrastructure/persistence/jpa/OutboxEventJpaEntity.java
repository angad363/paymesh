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
 * {@code published_at} is STILL deliberately unmapped, and now for a sharper reason than "no relay
 * exists". The relay exists (ADR-016), but this entity is {@code @Immutable}: Hibernate would never
 * flush a change to the field, so a mapped-and-assigned {@code publishedAt} would look like it
 * worked and silently do nothing. The relay therefore claims rows with a native query -- extra
 * columns in a native result are simply ignored -- and stamps them with a native UPDATE, which
 * bypasses entity state management entirely. See {@code SpringDataOutboxRepository}.
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

    // Read-only accessors, added for the relay's claim query. There are no setters and there will
    // not be: the table is append-only and the entity is @Immutable.

    public String eventId() {
        return eventId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
