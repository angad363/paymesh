package com.paymesh.ledger.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataLedgerTransactionRepository
    extends JpaRepository<LedgerTransactionJpaEntity, String> {

    Optional<LedgerTransactionJpaEntity> findByIdempotencyKey(String idempotencyKey);

    /**
     * Captures with no release journal yet, oldest first.
     * <p>
     * The NOT EXISTS is keyed on the same string {@code LedgerTransaction.fundsReleasedIdempotencyKey}
     * builds, which is what lets this job carry no state of its own: the ledger that did the
     * releasing is also the record of what has been released.
     */
    @Query("""
        select t.merchantId, t.referenceId, t.currency, t.occurredAt
        from LedgerTransactionJpaEntity t
        where t.transactionType = 'PAYMENT_CAPTURED'
          and not exists (
              select 1 from LedgerTransactionJpaEntity r
              where r.idempotencyKey = concat('funds-released:', t.referenceId)
          )
        order by t.occurredAt asc
        """)
    List<Object[]> findUnreleasedCaptures(Limit limit);

    /**
     * Every journal referencing one payment, locked. Native and {@code FOR UPDATE} because that is
     * the point -- see {@code LedgerTransactionRepository.lockPaymentJournals} for what it
     * serializes and why the ledger's usual answer (a deferred trigger) cannot.
     * <p>
     * Locks the CAPTURE journal among others, which is what makes this work at all: the capture is
     * posted before any refund or release can exist, so every writer that arrives later contends on
     * a row that is already there. A payment with no capture locks nothing and needs to lock
     * nothing.
     * <p>
     * <b>Rows only, never an entity.</b> These headers are {@code @Immutable} and nothing here
     * wants their fields; returning ids keeps Hibernate from materializing anything and keeps the
     * lock's cost to the lock.
     */
    @Query(
        value = """
            select t.ledger_transaction_id
            from ledger_transactions t
            where t.reference_type = 'PAYMENT_INTENT'
              and t.reference_id = :paymentIntentId
            for update
            """,
        nativeQuery = true
    )
    List<String> lockPaymentJournals(@Param("paymentIntentId") String paymentIntentId);
}
