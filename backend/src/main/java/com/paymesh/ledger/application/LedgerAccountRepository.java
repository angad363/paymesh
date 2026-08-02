package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerAccount;

import java.util.Optional;

/** The chart of accounts. Owned by {@code application}, implemented in {@code infrastructure}. */
public interface LedgerAccountRepository {

    /**
     * By the string address, which is the unique key (see V15). The reference is always built by a
     * {@link LedgerAccount} factory -- callers never spell one themselves.
     */
    Optional<LedgerAccount> findByReference(String accountReference);

    /**
     * Return the account at {@code candidate}'s reference, opening it if it is not there yet.
     *
     * <h2>THIS MUST NOT THROW ON A RACE, AND THAT IS WHY IT IS ONE METHOD RATHER THAN TWO</h2>
     *
     * The obvious shape is {@code findByReference(...).orElseGet(() -> save(...))} with a catch
     * around the save for the concurrent case. <b>That shape cannot work here.</b> This runs inside
     * the transaction {@code EventDispatcher} opened for the inbox claim and the posting, and in
     * PostgreSQL <i>any</i> error aborts the enclosing transaction: catching the constraint
     * violation leaves a transaction in which every subsequent statement fails with "current
     * transaction is aborted". The recovery read would be the first casualty, and the posting would
     * fail for a reason that has nothing to do with the posting.
     * <p>
     * So the race is resolved without ever raising an error -- an insert that does nothing when the
     * reference is taken, followed by a read that is the source of truth either way. Two concurrent
     * captures in a new currency both end up with the same account, which is what
     * {@code uq_ledger_accounts_reference} is for: two accounts at one address would each hold half
     * a balance, with no constraint violated and nothing anywhere reading as an error.
     */
    LedgerAccount open(LedgerAccount candidate);
}
