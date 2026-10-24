package com.paymesh.ledger.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code ledger_accounts} (V15). A persistence type, never the domain type (ADR-004). */
@Entity
@Table(name = "ledger_accounts")
public class LedgerAccountJpaEntity {

    @Id
    @Column(name = "ledger_account_id", nullable = false, length = 40)
    private String ledgerAccountId;

    @Column(name = "account_reference", nullable = false, length = 120)
    private String accountReference;

    /** Null for platform accounts. See V15's ck_ledger_accounts_owner. */
    @Column(name = "merchant_id", length = 40)
    private String merchantId;

    @Column(name = "account_type", nullable = false, length = 32)
    private String accountType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "normal_balance", nullable = false, length = 6)
    private String normalBalance;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccountJpaEntity() {
    }

    public LedgerAccountJpaEntity(
        String ledgerAccountId,
        String accountReference,
        String merchantId,
        String accountType,
        String currency,
        String normalBalance,
        Instant createdAt
    ) {
        this.ledgerAccountId = ledgerAccountId;
        this.accountReference = accountReference;
        this.merchantId = merchantId;
        this.accountType = accountType;
        this.currency = currency;
        this.normalBalance = normalBalance;
        this.createdAt = createdAt;
    }

    public String ledgerAccountId() {
        return ledgerAccountId;
    }

    public String accountReference() {
        return accountReference;
    }

    public String merchantId() {
        return merchantId;
    }

    public String accountType() {
        return accountType;
    }

    public String currency() {
        return currency;
    }

    public String normalBalance() {
        return normalBalance;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
