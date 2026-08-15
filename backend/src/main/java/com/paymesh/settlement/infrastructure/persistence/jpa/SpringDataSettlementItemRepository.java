package com.paymesh.settlement.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataSettlementItemRepository
    extends JpaRepository<SettlementItemJpaEntity, String> {

    List<SettlementItemJpaEntity> findBySettlementBatchIdOrderBySettlementItemIdAsc(
        String settlementBatchId
    );
}
