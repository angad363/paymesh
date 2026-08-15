package com.paymesh.ledger.infrastructure.persistence.jpa;

import com.paymesh.ledger.application.MerchantBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SpringDataLedgerEntryRepository extends JpaRepository<LedgerEntryJpaEntity, Long> {

    List<LedgerEntryJpaEntity> findByLedgerTransactionIdOrderByLedgerEntryIdAsc(
        String ledgerTransactionId
    );

    /**
     * One merchant's pending balance per currency, summed from the entries themselves.
     *
     * <h2>THE SIGN CONVENTION, WHICH IS THE ONLY SUBTLE PART</h2>
     *
     * {@code MERCHANT_PENDING} is a LIABILITY: it is money PayMesh owes the merchant, so a credit
     * increases it and a debit decreases it. Hence {@code credits - debits} rather than the
     * {@code debits - credits} that an asset account would want.
     * <p>
     * Getting this backwards is not a crash. It reports every merchant as owing PayMesh exactly
     * what PayMesh owes them, with every individual entry correct and no constraint violated --
     * which is why {@code ledger_accounts.normal_balance} is a stored column and why
     * {@link com.paymesh.ledger.domain.Direction#signedAgainst} exists: the domain states the rule
     * once, and a test compares this query's answer against it.
     *
     * <h2>THE DAY THE PREVIOUS VERSION OF THIS JAVADOC WARNED ABOUT HAS ARRIVED</h2>
     *
     * It said: "Today a merchant only owns pending accounts, so the type filter changes no result.
     * The day {@code MERCHANT_AVAILABLE} exists, a query that had only filtered by merchant would
     * silently start adding two different balances together and reporting the total as pending."
     * That day is V29. The type filter is now doing real work rather than standing by.
     * <p>
     * <b>Both figures come out of one pass</b>, split by a CASE on account type rather than by two
     * queries merged in Java. One scan, one group, and -- more to the point -- one place where the
     * sign convention is written. Two queries would be two chances to get {@code credits - debits}
     * backwards, and getting it backwards is not a crash: it reports every merchant as owing
     * PayMesh exactly what PayMesh owes them, with every entry correct and no constraint violated.
     * <p>
     * A merchant with entries in only one of the two accounts still gets one row per currency, with
     * the other figure summing to zero over an empty set of matching entries. That zero is honest:
     * the account exists as a concept now, and nothing is in it.
     */
    @Query("""
        select new com.paymesh.ledger.application.MerchantBalance(
            e.currency,
            sum(case when a.accountType = 'MERCHANT_PENDING'
                     then (case when e.direction = 'CREDIT'
                                then e.amountMinor else -e.amountMinor end)
                     else 0L end),
            sum(case when a.accountType = 'MERCHANT_AVAILABLE'
                     then (case when e.direction = 'CREDIT'
                                then e.amountMinor else -e.amountMinor end)
                     else 0L end),
            sum(case when a.accountType = 'SETTLEMENT_IN_TRANSIT'
                     then (case when e.direction = 'CREDIT'
                                then e.amountMinor else -e.amountMinor end)
                     else 0L end)
        )
        from LedgerEntryJpaEntity e, LedgerAccountJpaEntity a
        where a.ledgerAccountId = e.ledgerAccountId
          and a.merchantId = :merchantId
          and a.accountType in (
              'MERCHANT_PENDING', 'MERCHANT_AVAILABLE', 'SETTLEMENT_IN_TRANSIT'
          )
        group by e.currency
        order by e.currency
        """)
    List<MerchantBalance> balancesByMerchant(@Param("merchantId") String merchantId);

    /**
     * What each of a merchant's payments has contributed to its available account, per currency.
     *
     * <h2>THE QUERY SETTLEMENT CUTS A BATCH FROM</h2>
     *
     * Settlement needs two things that look different and are the same sum: how much a merchant can
     * be paid, and which payments that money came from. Rolling up the balance would answer the
     * first and lose the second, and SDD 17.1 wants a statement a merchant can reconcile against
     * their own orders.
     * <p>
     * A payment's contribution is its release credit minus every refund debited against available
     * afterwards, so a payment refunded past its own release contributes a NEGATIVE figure. That is
     * not an anomaly to filter out: dropping it would cut a batch for more than the merchant has.
     * <p>
     * <b>Journals referencing a batch are excluded on purpose.</b> The batch's own debit and a
     * returned payout's credit both live in this account and reference a {@code SETTLEMENT_BATCH},
     * not a payment. Settlement subtracts what it has already batched from these figures itself --
     * it owns {@code settlement_items} and the ledger does not -- and the arithmetic works out
     * because the V32 trigger makes a batch's debit equal the sum of its items.
     */
    @Query("""
        select e.currency, t.referenceId,
               sum(case when e.direction = 'CREDIT' then e.amountMinor else -e.amountMinor end)
        from LedgerEntryJpaEntity e, LedgerAccountJpaEntity a, LedgerTransactionJpaEntity t
        where a.ledgerAccountId = e.ledgerAccountId
          and t.ledgerTransactionId = e.ledgerTransactionId
          and a.merchantId = :merchantId
          and a.accountType = 'MERCHANT_AVAILABLE'
          and t.referenceType = 'PAYMENT_INTENT'
        group by e.currency, t.referenceId
        order by e.currency, t.referenceId
        """)
    List<Object[]> availableContributionsByMerchant(@Param("merchantId") String merchantId);

    /**
     * How much of one payment is still sitting in the merchant's PENDING account.
     *
     * <h2>THIS ONE QUERY IS WHY THE REFUND REVERSAL POINTS AT THE PAYMENT</h2>
     *
     * Every journal that touches a payment's pending position references that payment: the capture
     * credits it, a refund reversal debits it (since V29), and a release debits it. Summing the
     * pending-account lines of all of them, signed, gives exactly "what is left to release" with no
     * special cases -- a partial refund is already subtracted, two partial refunds are both
     * subtracted, and a payment already released sums to zero because its own release is in the sum.
     * <p>
     * That last property is what makes the release job idempotent in arithmetic as well as by
     * unique key: re-running it on a released payment computes zero and posts nothing.
     * <p>
     * Signed the liability way -- credits minus debits -- for the reason the balance query above
     * gives at length. Getting it backwards would report a payment as owing money the instant it
     * was captured.
     */
    @Query("""
        select coalesce(sum(case when e.direction = 'CREDIT'
                                 then e.amountMinor else -e.amountMinor end), 0L)
        from LedgerEntryJpaEntity e, LedgerAccountJpaEntity a, LedgerTransactionJpaEntity t
        where a.ledgerAccountId = e.ledgerAccountId
          and t.ledgerTransactionId = e.ledgerTransactionId
          and a.accountType = 'MERCHANT_PENDING'
          and t.referenceType = 'PAYMENT_INTENT'
          and t.referenceId = :paymentIntentId
        """)
    long pendingRemainingForPayment(@Param("paymentIntentId") String paymentIntentId);
}
