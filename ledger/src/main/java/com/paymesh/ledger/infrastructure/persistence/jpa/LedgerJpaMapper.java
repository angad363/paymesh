package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.domain.AccountType;
import com.paymesh.ledger.domain.Direction;
import com.paymesh.ledger.domain.LedgerAccount;
import com.paymesh.ledger.domain.LedgerAccountId;
import com.paymesh.ledger.domain.LedgerEntry;
import com.paymesh.ledger.domain.LedgerTransaction;
import com.paymesh.ledger.domain.LedgerTransactionId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/** Domain <-> persistence, both directions, in one place (ADR-004). */
final class LedgerJpaMapper {

    private LedgerJpaMapper() {
    }

    static LedgerAccountJpaEntity toEntity(LedgerAccount account) {
        return new LedgerAccountJpaEntity(
            account.ledgerAccountId().value(),
            account.accountReference(),
            account.merchantId() == null ? null : account.merchantId().value(),
            account.accountType().name(),
            account.currency(),
            // Derived from the type rather than stored on the domain object. The column exists so
            // the table is readable on its own -- an operator running a SUM against it should not
            // have to know that MERCHANT_PENDING is a liability -- but AccountType is the authority,
            // and writing it from there is what keeps the two from disagreeing.
            account.normalBalance().name(),
            account.createdAt()
        );
    }

    static LedgerAccount toDomain(LedgerAccountJpaEntity entity) {
        return new LedgerAccount(
            LedgerAccountId.from(entity.ledgerAccountId()),
            entity.accountReference(),
            entity.merchantId() == null ? null : MerchantId.from(entity.merchantId()),
            AccountType.valueOf(entity.accountType()),
            entity.currency(),
            entity.createdAt()
        );
    }

    static LedgerTransactionJpaEntity toEntity(LedgerTransaction transaction) {
        return new LedgerTransactionJpaEntity(
            transaction.ledgerTransactionId().value(),
            transaction.merchantId().value(),
            transaction.transactionType(),
            transaction.referenceType(),
            transaction.referenceId(),
            transaction.currency(),
            transaction.idempotencyKey(),
            transaction.occurredAt(),
            transaction.createdAt()
        );
    }

    static LedgerEntryJpaEntity toEntity(LedgerTransaction transaction, LedgerEntry entry) {
        return new LedgerEntryJpaEntity(
            transaction.ledgerTransactionId().value(),
            entry.ledgerAccountId().value(),
            entry.direction().name(),
            entry.amountMinor(),
            // From the TRANSACTION, never from the entry -- the entry does not carry one. That is
            // deliberate: the composite foreign keys make an entry's currency equal to both its
            // transaction's and its account's, so a currency on LedgerEntry would be a third copy
            // of one fact and the only one that could be wrong.
            transaction.currency(),
            transaction.createdAt()
        );
    }

    static LedgerTransaction toDomain(
        LedgerTransactionJpaEntity header,
        List<LedgerEntryJpaEntity> entries
    ) {
        return new LedgerTransaction(
            LedgerTransactionId.from(header.ledgerTransactionId()),
            MerchantId.from(header.merchantId()),
            header.transactionType(),
            header.referenceType(),
            header.referenceId(),
            header.currency(),
            header.idempotencyKey(),
            entries.stream().map(LedgerJpaMapper::toDomain).toList(),
            header.occurredAt(),
            header.createdAt()
        );
    }

    private static LedgerEntry toDomain(LedgerEntryJpaEntity entity) {
        return new LedgerEntry(
            LedgerAccountId.from(entity.ledgerAccountId()),
            Direction.valueOf(entity.direction()),
            entity.amountMinor()
        );
    }
}
