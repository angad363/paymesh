package com.paymesh.ledger.application;

import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/** The balance read model. */
public interface BalanceRepository {

    /**
     * Every currency this merchant holds a pending balance in, ordered by currency.
     *
     * <h2>SUMMED FROM THE ENTRIES, NOT READ FROM A PROJECTION</h2>
     *
     * SDD 15.5 specifies an {@code account_balances} table -- "fast authoritative projection", with
     * debit and credit totals and a version column. It is deliberately not built (ADR-018 section
     * 5).
     * <p>
     * A projection is a second copy of a number the entries already determine, and the failure mode
     * of a second copy is that it disagrees with the first. That disagreement is silent, it is
     * discovered by a merchant rather than by a test, and the repair is to recompute it from the
     * entries -- which is this query. A SUM cannot drift from what it sums.
     * <p>
     * The cost is real and bounded: this is O(entries for one merchant), and it grows for the life
     * of the account. {@code ix_ledger_entries_account} covers direction and amount so it stays an
     * index-only scan, which pushes the problem out a long way but does not remove it.
     * <p>
     * ponytail: SUM per read; add {@code account_balances} when a balance read measures slow, and
     * rebuild it from this query.
     */
    List<MerchantBalance> pendingBalances(MerchantId merchantId);
}
