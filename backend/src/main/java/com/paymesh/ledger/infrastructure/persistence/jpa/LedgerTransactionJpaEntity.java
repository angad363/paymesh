package com.paymesh.ledger.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code ledger_transactions} (V15) -- the journal header only; entries are their own type. */
@Entity
@Table(name = "ledger_transactions")
public class LedgerTransactionJpaEntity {

    @Id
    @Column(name = "ledger_transaction_id", nullable = false, length = 40)
    private String ledgerTransactionId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "transaction_type", nullable = false, length = 40)
    private String transactionType;

    @Column(name = "reference_type", nullable = false, length = 32)
    private String referenceType;

    @Column(name = "reference_id", nullable = false, length = 40)
    private String referenceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerTransactionJpaEntity() {
    }

    public LedgerTransactionJpaEntity(
        String ledgerTransactionId,
        String merchantId,
        String transactionType,
        String referenceType,
        String referenceId,
        String currency,
        String idempotencyKey,
        Instant occurredAt,
        Instant createdAt
    ) {
        this.ledgerTransactionId = ledgerTransactionId;
        this.merchantId = merchantId;
        this.transactionType = transactionType;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public String ledgerTransactionId() {
        return ledgerTransactionId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String transactionType() {
        return transactionType;
    }

    public String referenceType() {
        return referenceType;
    }

    public String referenceId() {
        return referenceId;
    }

    public String currency() {
        return currency;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
