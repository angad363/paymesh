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
                     else 0L end)
        )
        from LedgerEntryJpaEntity e, LedgerAccountJpaEntity a
        where a.ledgerAccountId = e.ledgerAccountId
          and a.merchantId = :merchantId
          and a.accountType in ('MERCHANT_PENDING', 'MERCHANT_AVAILABLE')
        group by e.currency
        order by e.currency
        """)
    List<MerchantBalance> balancesByMerchant(@Param("merchantId") String merchantId);
}
