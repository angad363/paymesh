package com.paymesh.ledger.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SpringDataLedgerAccountRepository
    extends JpaRepository<LedgerAccountJpaEntity, String> {

    Optional<LedgerAccountJpaEntity> findByAccountReference(String accountReference);

    /**
     * The merchants holding an account of one type. Settlement's candidate list.
     * <p>
     * Distinct ids rather than entities: nothing wants the account rows, and a merchant holds one
     * account of a type per currency, so the entity version would return duplicates the caller then
     * has to fold.
     */
    @Query("""
        select distinct a.merchantId
        from LedgerAccountJpaEntity a
        where a.accountType = :accountType
          and a.merchantId is not null
        order by a.merchantId
        """)
    java.util.List<String> merchantsWithAccountType(@Param("accountType") String accountType);

    /**
     * Insert the account unless its reference is already taken.
     *
     * <h2>A NATIVE {@code ON CONFLICT DO NOTHING}, AND IT HAS TO BE</h2>
     *
     * This runs inside the transaction the event dispatcher opened for the inbox claim and the
     * posting. A duplicate-key <i>error</i> there would abort that whole transaction in PostgreSQL,
     * and every statement after it -- including the read that would recover from the race -- would
     * fail with "current transaction is aborted". The recovery has to happen without an error being
     * raised at all, and {@code ON CONFLICT DO NOTHING} is the only way to insert-if-absent without
     * raising one.
     * <p>
     * JPQL cannot express it: the clause is PostgreSQL's, not JPA's. That is what
     * {@code nativeQuery} is buying, and it is the reason this method is written out rather than
     * derived.
     * <p>
     * The caller reads the row afterwards either way, so the return count is ignored -- 1 means
     * this call opened the account, 0 means a concurrent one did. Both leave exactly one account at
     * that reference, which is the only outcome that matters.
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO ledger_accounts (
                ledger_account_id, account_reference, merchant_id,
                account_type, currency, normal_balance, created_at
            )
            VALUES (
                :ledgerAccountId, :accountReference, :merchantId,
                :accountType, :currency, :normalBalance, :createdAt
            )
            ON CONFLICT (account_reference) DO NOTHING
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("ledgerAccountId") String ledgerAccountId,
        @Param("accountReference") String accountReference,
        @Param("merchantId") String merchantId,
        @Param("accountType") String accountType,
        @Param("currency") String currency,
        @Param("normalBalance") String normalBalance,
        @Param("createdAt") Instant createdAt
    );
}
