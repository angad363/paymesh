package com.paymesh.settlement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A row of {@code payouts}. Mutable, unlike the batch it pays: attempts and status both move. */
@Entity
@Table(name = "payouts")
public class PayoutJpaEntity {

    @Id
    @Column(name = "payout_id", nullable = false, updatable = false, length = 40)
    private String payoutId;

    @Column(name = "settlement_batch_id", nullable = false, updatable = false, length = 40)
    private String settlementBatchId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "destination", nullable = false, updatable = false, length = 80)
    private String destination;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "provider_reference", length = 60)
    private String providerReference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayoutJpaEntity() {
    }

    public PayoutJpaEntity(
        String payoutId,
        String settlementBatchId,
        String merchantId,
        long amountMinor,
        String currency,
        String destination,
        String status,
        int attempts,
        Instant nextAttemptAt,
        String lastError,
        String providerReference,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.payoutId = payoutId;
        this.settlementBatchId = settlementBatchId;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.destination = destination;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastError = lastError;
        this.providerReference = providerReference;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String payoutId() {
        return payoutId;
    }

    public String settlementBatchId() {
        return settlementBatchId;
    }

    public String merchantId() {
        return merchantId;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String destination() {
        return destination;
    }

    public String status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public String lastError() {
        return lastError;
    }

    public String providerReference() {
        return providerReference;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
