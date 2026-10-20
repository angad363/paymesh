package com.paymesh.settlement.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataPayoutRepository extends JpaRepository<PayoutJpaEntity, String> {

    Optional<PayoutJpaEntity> findBySettlementBatchId(String settlementBatchId);

    /**
     * Due payout IDS, oldest first.
     * <p>
     * IDS rather than entities, and that is not a style choice: mapping a row inside the repository
     * call puts it outside the caller's per-item try, which is exactly how one unreadable row
     * disabled five sweeps in this codebase (open item 2).
     * <p>
     * ONLY PAYOUTS WHOSE BATCH THE LEDGER HAS POSTED ({@code POSTED}). Submitting earlier would let
     * the provider pay a batch the Ledger may still reject (ADR-047 section 8).
     */
    @Query("""
        select p.payoutId
        from PayoutJpaEntity p
        where p.status in ('PENDING', 'SUBMITTED')
          and p.nextAttemptAt <= :now
          and exists (
              select 1 from SettlementBatchJpaEntity b
              where b.settlementBatchId = p.settlementBatchId and b.status = 'POSTED'
          )
        order by p.nextAttemptAt asc
        """)
    List<String> findDue(@Param("now") Instant now, Limit limit);

    /** {@code SELECT ... FOR UPDATE}, so two sweeps cannot submit one payout twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PayoutJpaEntity p where p.payoutId = :payoutId")
    Optional<PayoutJpaEntity> findForUpdate(@Param("payoutId") String payoutId);
}
