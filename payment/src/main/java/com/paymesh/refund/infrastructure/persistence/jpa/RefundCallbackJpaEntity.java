package com.paymesh.refund.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code refund_callbacks} (V16) -- the dedup and audit record of what a provider sent. */
@Entity
@Table(name = "refund_callbacks")
@IdClass(RefundCallbackJpaId.class)
public class RefundCallbackJpaEntity {

    @Id
    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Id
    @Column(name = "external_event_id", nullable = false, length = 100)
    private String externalEventId;

    @Column(name = "refund_id", nullable = false, length = 40)
    private String refundId;

    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected RefundCallbackJpaEntity() {
    }

    public RefundCallbackJpaEntity(
        String provider,
        String externalEventId,
        String refundId,
        String outcome,
        String payloadHash,
        Instant occurredAt,
        Instant receivedAt
    ) {
        this.provider = provider;
        this.externalEventId = externalEventId;
        this.refundId = refundId;
        this.outcome = outcome;
        this.payloadHash = payloadHash;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
    }

    public String refundId() {
        return refundId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
