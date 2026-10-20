package com.paymesh.settlement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A row of {@code settlement_batches}.
 *
 * <p>Not {@code @Immutable}, unlike {@link SettlementItemJpaEntity}: the status has to move from
 * PENDING_PAYOUT. Everything else is protected by {@code tr_settlement_batches_append_only}, which
 * compares the new row against the old and refuses any other change -- so a mapper that
 * accidentally rewrote the amount fails at the database rather than silently succeeding.
 */
@Entity
@Table(name = "settlement_batches")
public class SettlementBatchJpaEntity {

    @Id
    @Column(name = "settlement_batch_id", nullable = false, updatable = false, length = 40)
    private String settlementBatchId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "net_amount_minor", nullable = false, updatable = false)
    private long netAmountMinor;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "cut_at", nullable = false, updatable = false)
    private Instant cutAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SettlementBatchJpaEntity() {
    }

    public SettlementBatchJpaEntity(
        String settlementBatchId,
        String merchantId,
        String currency,
        long netAmountMinor,
        String status,
        Instant cutAt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.settlementBatchId = settlementBatchId;
        this.merchantId = merchantId;
        this.currency = currency;
        this.netAmountMinor = netAmountMinor;
        this.status = status;
        this.cutAt = cutAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String settlementBatchId() {
        return settlementBatchId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String currency() {
        return currency;
    }

    public long netAmountMinor() {
        return netAmountMinor;
    }

    public String status() {
        return status;
    }

    public Instant cutAt() {
        return cutAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    void applyStatus(String status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }
}
