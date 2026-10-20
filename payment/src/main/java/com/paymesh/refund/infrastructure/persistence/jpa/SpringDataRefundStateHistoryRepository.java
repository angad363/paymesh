package com.paymesh.refund.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataRefundStateHistoryRepository
    extends JpaRepository<RefundStateHistoryJpaEntity, Long> {
}
