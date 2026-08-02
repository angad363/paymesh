package com.paymesh.merchant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataMerchantStatusHistoryRepository
    extends JpaRepository<MerchantStatusHistoryJpaEntity, Long> {
}
