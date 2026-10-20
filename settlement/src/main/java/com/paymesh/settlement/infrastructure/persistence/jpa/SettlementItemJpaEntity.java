package com.paymesh.settlement.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A row of {@code settlement_items}.
 *
 * <p>{@code @Immutable} because the database says so: {@code tr_settlement_items_immutable} refuses
 * UPDATE and DELETE outright. Hibernate knowing that keeps it from issuing a write nothing would
 * accept -- and, more usefully, keeps a dirty-checking flush from turning a read into an error at a
 * moment nobody is looking at this table.
 */
@Entity
@Immutable
@Table(name = "settlement_items")
public class SettlementItemJpaEntity {

    @Id
    @Column(name = "settlement_item_id", nullable = false, updatable = false, length = 40)
    private String settlementItemId;

    @Column(name = "settlement_batch_id", nullable = false, updatable = false, length = 40)
    private String settlementBatchId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "payment_intent_id", nullable = false, updatable = false, length = 40)
    private String paymentIntentId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SettlementItemJpaEntity() {
    }

    public SettlementItemJpaEntity(
        String settlementItemId,
        String settlementBatchId,
        String merchantId,
        String currency,
        String paymentIntentId,
        long amountMinor,
        Instant createdAt
    ) {
        this.settlementItemId = settlementItemId;
        this.settlementBatchId = settlementBatchId;
        this.merchantId = merchantId;
        this.currency = currency;
        this.paymentIntentId = paymentIntentId;
        this.amountMinor = amountMinor;
        this.createdAt = createdAt;
    }

    public String settlementItemId() {
        return settlementItemId;
    }

    public String settlementBatchId() {
        return settlementBatchId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }

    public long amountMinor() {
        return amountMinor;
    }
}
