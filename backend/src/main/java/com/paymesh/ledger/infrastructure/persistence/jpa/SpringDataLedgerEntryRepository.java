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
     * <h2>Why the join filters on account_type as well as merchant</h2>
     *
     * Belt and braces. Today a merchant only owns pending accounts, so the type filter changes no
     * result. The day {@code MERCHANT_AVAILABLE} exists, a query that had only filtered by merchant
     * would silently start adding two different balances together and reporting the total as
     * "pending".
     */
    @Query("""
        select new com.paymesh.ledger.application.MerchantBalance(
            e.currency,
            sum(case when e.direction = 'CREDIT' then e.amountMinor else -e.amountMinor end)
        )
        from LedgerEntryJpaEntity e, LedgerAccountJpaEntity a
        where a.ledgerAccountId = e.ledgerAccountId
          and a.merchantId = :merchantId
          and a.accountType = 'MERCHANT_PENDING'
        group by e.currency
        order by e.currency
        """)
    List<MerchantBalance> pendingBalancesByMerchant(@Param("merchantId") String merchantId);
}
