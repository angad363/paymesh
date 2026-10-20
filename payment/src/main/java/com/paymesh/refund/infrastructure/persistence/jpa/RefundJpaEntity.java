package com.paymesh.refund.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code refunds} (V16). A persistence type, never the domain type (ADR-004). */
@Entity
@Table(name = "refunds")
public class RefundJpaEntity {

    @Id
    @Column(name = "refund_id", nullable = false, length = 40)
    private String refundId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "payment_intent_id", nullable = false, length = 40)
    private String paymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "merchant_reference", length = 100)
    private String merchantReference;

    @Column(name = "reason", length = 200)
    private String reason;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RefundJpaEntity() {
    }

    public RefundJpaEntity(
        String refundId,
        String merchantId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        String status,
        String merchantReference,
        String reason,
        String providerReference,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.refundId = refundId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = status;
        this.merchantReference = merchantReference;
        this.reason = reason;
        this.providerReference = providerReference;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String refundId() {
        return refundId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String status() {
        return status;
    }

    public String merchantReference() {
        return merchantReference;
    }

    public String reason() {
        return reason;
    }

    public String providerReference() {
        return providerReference;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
