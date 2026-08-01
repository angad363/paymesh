package com.paymesh.order.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistence model for order_state_history (V11__create_order_state_history.sql).
 * <p>
 * Append-only: there is no setter and no update path anywhere in the codebase. A history that can
 * be rewritten is not a history.
 */
@Entity
@Table(name = "order_state_history")
public class OrderStateHistoryJpaEntity {

    /**
     * A database-generated sequence rather than a prefixed identifier: ADR-003 governs identifiers
     * that appear in an API, and this row's id never does. IDENTITY matches the column's
     * {@code GENERATED ALWAYS AS IDENTITY}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_state_history_id", nullable = false)
    private Long orderStateHistoryId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    /** Null for the creation row. */
    @Column(name = "from_status", length = 32)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 32)
    private String toStatus;

    @Column(name = "actor_type", nullable = false, length = 20)
    private String actorType;

    @Column(name = "actor_id", length = 80)
    private String actorId;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** Required by JPA. Not for application use. */
    protected OrderStateHistoryJpaEntity() {
    }

    public OrderStateHistoryJpaEntity(
        String merchantId,
        String orderId,
        String fromStatus,
        String toStatus,
        String actorType,
        String actorId,
        String reason,
        Instant occurredAt
    ) {
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = occurredAt;
    }

    public Long orderStateHistoryId() {
        return orderStateHistoryId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String orderId() {
        return orderId;
    }

    public String fromStatus() {
        return fromStatus;
    }

    public String toStatus() {
        return toStatus;
    }

    public String actorType() {
        return actorType;
    }

    public String actorId() {
        return actorId;
    }

    public String reason() {
        return reason;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
