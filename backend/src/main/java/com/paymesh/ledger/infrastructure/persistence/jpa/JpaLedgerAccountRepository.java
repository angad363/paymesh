package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.application.LedgerAccountRepository;
import com.paymesh.ledger.domain.LedgerAccount;

import java.util.Optional;

public final class JpaLedgerAccountRepository implements LedgerAccountRepository {

    private final SpringDataLedgerAccountRepository accounts;

    public JpaLedgerAccountRepository(SpringDataLedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public Optional<LedgerAccount> findByReference(String accountReference) {
        return accounts.findByAccountReference(accountReference).map(LedgerJpaMapper::toDomain);
    }

    @Override
    public LedgerAccount open(LedgerAccount candidate) {
        accounts.insertIfAbsent(
            candidate.ledgerAccountId().value(),
            candidate.accountReference(),
            candidate.merchantId() == null ? null : candidate.merchantId().value(),
            candidate.accountType().name(),
            candidate.currency(),
            candidate.normalBalance().name(),
            candidate.createdAt()
        );

        // ALWAYS RE-READ, never return the candidate. When the insert did nothing because a
        // concurrent posting won the race, the candidate holds a ledgerAccountId that was generated
        // here and never written -- returning it would have the entries reference an account row
        // that does not exist, and the foreign key would refuse them at flush. The row in the
        // database is the answer whether this call wrote it or not.
        return findByReference(candidate.accountReference()).orElseThrow(() ->
            new IllegalStateException(
                "Ledger account " + candidate.accountReference() + " was neither inserted nor found"
            )
        );
    }
}
