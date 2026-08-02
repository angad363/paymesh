package com.paymesh.ledger.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Maps {@code ledger_entries} (V15).
 *
 * <h2>NO {@code @ManyToOne} TO THE TRANSACTION, AND NO CASCADE</h2>
 *
 * The obvious mapping is a {@code @OneToMany(cascade = ALL)} from the header so that saving a
 * journal saves its lines. It is avoided for two reasons.
 * <p>
 * First, cascade implies the inverse: a JPA association that cascades persist will happily cascade
 * remove, and {@code tr_ledger_entries_immutable} would then turn an ordinary-looking
 * {@code delete(transaction)} into a runtime exception from a trigger. Not modelling the
 * association means the delete cannot be written in the first place.
 * <p>
 * Second, the foreign key is composite -- {@code (ledger_transaction_id, currency)}, which is what
 * makes a single-currency journal structurally impossible to violate. Mapping that as an
 * association gets JPA involved in a key whose second column is also a mapped attribute, and the
 * insert order Hibernate picks for a deferred-constraint table is then something to reason about
 * rather than something to state. The adapter writes the header, flushes, writes the entries. That
 * order is explicit and it is the order the foreign key needs.
 */
@Entity
@Table(name = "ledger_entries")
public class LedgerEntryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_entry_id", nullable = false)
    private Long ledgerEntryId;

    @Column(name = "ledger_transaction_id", nullable = false, length = 40)
    private String ledgerTransactionId;

    @Column(name = "ledger_account_id", nullable = false, length = 40)
    private String ledgerAccountId;

    @Column(name = "direction", nullable = false, length = 6)
    private String direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntryJpaEntity() {
    }

    public LedgerEntryJpaEntity(
        String ledgerTransactionId,
        String ledgerAccountId,
        String direction,
        long amountMinor,
        String currency,
        Instant createdAt
    ) {
        this.ledgerTransactionId = ledgerTransactionId;
        this.ledgerAccountId = ledgerAccountId;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public Long ledgerEntryId() {
        return ledgerEntryId;
    }

    public String ledgerTransactionId() {
        return ledgerTransactionId;
    }

    public String ledgerAccountId() {
        return ledgerAccountId;
    }

    public String direction() {
        return direction;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
