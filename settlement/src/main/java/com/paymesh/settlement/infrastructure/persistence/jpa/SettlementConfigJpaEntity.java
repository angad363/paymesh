package com.paymesh.settlement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row of {@code settlement_configs}. The merchant is the key; there is no surrogate id. */
@Entity
@Table(name = "settlement_configs")
public class SettlementConfigJpaEntity {

    @Id
    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "holding_period_seconds", nullable = false)
    private int holdingPeriodSeconds;

    /** Null means "not configured", which is what stops this merchant being batched at all. */
    @Column(name = "payout_destination", length = 80)
    private String payoutDestination;

    @Column(name = "minimum_payout_minor", nullable = false)
    private long minimumPayoutMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettlementConfigJpaEntity() {
    }

    public SettlementConfigJpaEntity(
        String merchantId,
        int holdingPeriodSeconds,
        String payoutDestination,
        long minimumPayoutMinor,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.merchantId = merchantId;
        this.holdingPeriodSeconds = holdingPeriodSeconds;
        this.payoutDestination = payoutDestination;
        this.minimumPayoutMinor = minimumPayoutMinor;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String merchantId() {
        return merchantId;
    }

    public int holdingPeriodSeconds() {
        return holdingPeriodSeconds;
    }

    public String payoutDestination() {
        return payoutDestination;
    }

    public long minimumPayoutMinor() {
        return minimumPayoutMinor;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
