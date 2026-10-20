package com.paymesh.customer.infrastructure.persistence.jpa;

import jakarta.persistence.*;

import java.time.Instant;

/** Maps {@code customer_status_history} (V17). */
@Entity
@Table(name = "customer_status_history")
public class CustomerStatusHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "customer_status_history_id", nullable = false)
    private Long customerStatusHistoryId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "customer_id", nullable = false, length = 40)
    private String customerId;

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

    protected CustomerStatusHistoryJpaEntity() {
    }

    public CustomerStatusHistoryJpaEntity(
        String merchantId, String customerId, String fromStatus, String toStatus,
        String actorType, String actorId, String reason, Instant occurredAt
    ) {
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }
}
