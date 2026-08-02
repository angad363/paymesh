package com.paymesh.simulator.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for {@code provider_payments} (V13__create_provider_simulator.sql).
 * Column definitions must match that migration -- {@code ddl-auto=validate} fails startup on drift.
 * <p>
 * Separate from {@link com.paymesh.simulator.domain.SimulatedPayment} with a hand-written mapper
 * (ADR-004): the aggregate has a state machine and no setters, and JPA needs both.
 */
@Entity
@Table(name = "provider_payments")
public class SimulatedPaymentJpaEntity {

    @Id
    @Column(name = "provider_payment_id", nullable = false, length = 50)
    private String providerPaymentId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    // CHAR(64) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and
    // schema validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    // The CALLER's reference, echoed into every callback. Not a foreign key and not state.
    @Column(name = "callback_reference", nullable = false, length = 60)
    private String callbackReference;

    @Column(name = "method", nullable = false, length = 20)
    private String method;

    // A deterministic test token, never anything derived from a real instrument (SDD 4.2, 13.6).
    @Column(name = "token", nullable = false, length = 60)
    private String token;

    // Resolved from the token at create time and FROZEN. A later failure-profile change must not
    // make a payment already in progress change its mind.
    @Column(name = "behaviour", nullable = false, length = 30)
    private String behaviour;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    // CHAR(3) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "capture_method", nullable = false, length = 10)
    private String captureMethod;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected SimulatedPaymentJpaEntity() {
    }

    SimulatedPaymentJpaEntity(
        String providerPaymentId,
        String idempotencyKey,
        String requestHash,
        String callbackReference,
        String method,
        String token,
        String behaviour,
        long amountMinor,
        String currency,
        String captureMethod,
        String status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.providerPaymentId = providerPaymentId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.callbackReference = callbackReference;
        this.method = method;
        this.token = token;
        this.behaviour = behaviour;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.captureMethod = captureMethod;
        this.status = status;
        this.capturedAmountMinor = capturedAmountMinor;
        this.refundedAmountMinor = refundedAmountMinor;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    String providerPaymentId() {
        return providerPaymentId;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    String requestHash() {
        return requestHash;
    }

    String callbackReference() {
        return callbackReference;
    }

    String method() {
        return method;
    }

    String token() {
        return token;
    }

    String behaviour() {
        return behaviour;
    }

    long amountMinor() {
        return amountMinor;
    }

    String currency() {
        return currency;
    }

    String captureMethod() {
        return captureMethod;
    }

    String status() {
        return status;
    }

    long capturedAmountMinor() {
        return capturedAmountMinor;
    }

    long refundedAmountMinor() {
        return refundedAmountMinor;
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
