package com.paymesh.ledger.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Query;
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
}
