package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for {@code provider_refunds} (V13__create_provider_simulator.sql).
 * Column definitions must match that migration -- {@code ddl-auto=validate} fails startup on drift.
 */
@Entity
@Table(name = "provider_refunds")
public class SimulatedRefundJpaEntity {

    @Id
    @Column(name = "provider_refund_id", nullable = false, length = 50)
    private String providerRefundId;

    @Column(name = "provider_payment_id", nullable = false, length = 50)
    private String providerPaymentId;

    // Nullable: rows written before V22 have none, and a caller may legitimately send none.
    @Column(name = "callback_reference", length = 120)
    private String callbackReference;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected SimulatedRefundJpaEntity() {
    }

    SimulatedRefundJpaEntity(
        String providerRefundId,
        String providerPaymentId,
        String callbackReference,
        String idempotencyKey,
        String requestHash,
        long amountMinor,
        String status,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.providerRefundId = providerRefundId;
        this.providerPaymentId = providerPaymentId;
        this.callbackReference = callbackReference;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.amountMinor = amountMinor;
        this.status = status;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String providerRefundId() {
        return providerRefundId;
    }

    String providerPaymentId() {
        return providerPaymentId;
    }

    String callbackReference() {
        return callbackReference;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    String requestHash() {
        return requestHash;
    }

    long amountMinor() {
        return amountMinor;
    }

    String status() {
        return status;
    }

    String failureCode() {
        return failureCode;
    }

    String failureMessage() {
        return failureMessage;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
