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
    List<MerchantBalance> byMerchant(MerchantId merchantId);

    /**
     * What is left of one payment in the merchant's pending account, signed the liability way.
     * <p>
     * Zero for a payment already released -- its own release journal is part of the sum -- which is
     * what makes {@code ReleaseAvailableFundsService} idempotent in arithmetic and not only by
     * unique key.
     */
    long pendingRemainingForPayment(String paymentIntentId);

    /**
     * What each of a merchant's payments has contributed to its AVAILABLE account, per currency.
     * <p>
     * The read Settlement cuts a batch from: the sum of these figures is the available balance, and
     * each one names the payment it came from so a statement can be reconciled against orders. A
     * payment refunded past its own release contributes a negative figure, which is a real
     * adjustment rather than a row to drop.
     */
    List<AvailableContribution> availableContributionsByMerchant(MerchantId merchantId);

    /**
     * Every merchant with a {@code MERCHANT_AVAILABLE} account, ordered.
     * <p>
     * From {@code ledger_accounts} rather than from a balance: an account exists because something
     * was released into it, so this is "every merchant who has ever been settleable" -- a cheap
     * candidate list rather than an expensive answer.
     */
    List<MerchantId> merchantsWithAnAvailableAccount();
}
