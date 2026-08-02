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
        //
        // RARE, NOT IMPOSSIBLE, AND SELF-HEALING. The throw below needs a narrow race: a concurrent
        // transaction inserts this reference, our ON CONFLICT DO NOTHING waits on its uncommitted
        // index entry and takes no action, and that transaction then ROLLS BACK -- leaving us having
        // inserted nothing and finding nothing. The recovery is the one the whole module leans on:
        // the throw rolls back the dispatcher's transaction along with the inbox row claiming the
        // event, so the event is redelivered and the next attempt opens the account cleanly.
        return findByReference(candidate.accountReference()).orElseThrow(() ->
            new IllegalStateException(
                "Ledger account " + candidate.accountReference() + " was neither inserted nor found"
                    + "; a concurrent opener most likely rolled back, and redelivery will retry"
            )
        );
    }
}
