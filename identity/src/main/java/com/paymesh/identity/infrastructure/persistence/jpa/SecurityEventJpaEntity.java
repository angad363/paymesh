package com.paymesh.identity.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Persistence model for the append-only security_events table. */
@Entity
@Table(name = "security_events")
public class SecurityEventJpaEntity {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 48)
    private String eventType;

    @Column(name = "actor", length = 320)
    private String actor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Required by JPA. Not for application use. */
    protected SecurityEventJpaEntity() {
    }

    public SecurityEventJpaEntity(
        String eventId,
        String eventType,
        String actor,
        String ipHash,
        Instant occurredAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.actor = actor;
        this.ipHash = ipHash;
        this.occurredAt = occurredAt;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String actor() {
        return actor;
    }

    public String ipHash() {
        return ipHash;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
