package com.paymesh.refund.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps {@code refund_state_history} (V16). */
@Entity
@Table(name = "refund_state_history")
public class RefundStateHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_state_history_id", nullable = false)
    private Long refundStateHistoryId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "refund_id", nullable = false, length = 40)
    private String refundId;

    @Column(name = "from_status", length = 20)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20)
    private String toStatus;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", length = 80)
    private String actorId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected RefundStateHistoryJpaEntity() {
    }

    public RefundStateHistoryJpaEntity(
        String merchantId,
        String refundId,
        String fromStatus,
        String toStatus,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        this.merchantId = merchantId;
        this.refundId = refundId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public Long refundStateHistoryId() {
        return refundStateHistoryId;
    }
}
