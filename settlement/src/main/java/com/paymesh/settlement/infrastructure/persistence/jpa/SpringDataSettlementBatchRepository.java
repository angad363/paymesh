package com.paymesh.settlement.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpringDataSettlementBatchRepository
    extends JpaRepository<SettlementBatchJpaEntity, String> {

    /** MERCHANT-SCOPED, always. An id alone never authorises access (tenant isolation). */
    Optional<SettlementBatchJpaEntity> findBySettlementBatchIdAndMerchantId(
        String settlementBatchId, String merchantId
    );

    List<SettlementBatchJpaEntity> findByMerchantIdOrderByCutAtDescSettlementBatchIdDesc(
        String merchantId, Limit limit
    );

    /**
     * How much of each payment this merchant has already committed to a batch.
     *
     * <h2>RETURNED BATCHES ARE EXCLUDED, AND GETTING THAT WRONG SHRINKS EVERY LATER BATCH</h2>
     *
     * A returned batch gave its funds back to available through a new ledger journal. The ledger
     * therefore already reports that money as settleable again -- so counting the returned batch's
     * items here would subtract it a second time, and the merchant would quietly be paid less than
     * they are owed, once per failed payout, forever.
     */
    @Query("""
        select i.currency, i.paymentIntentId, sum(i.amountMinor)
        from SettlementItemJpaEntity i, SettlementBatchJpaEntity b
        where b.settlementBatchId = i.settlementBatchId
          and i.merchantId = :merchantId
          and b.status <> 'RETURNED'
        group by i.currency, i.paymentIntentId
        """)
    List<Object[]> batchedAmounts(@Param("merchantId") String merchantId);
}
