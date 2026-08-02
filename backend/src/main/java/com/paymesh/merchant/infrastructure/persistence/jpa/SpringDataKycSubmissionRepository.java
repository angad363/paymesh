package com.paymesh.merchant.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataKycSubmissionRepository
    extends JpaRepository<KycSubmissionJpaEntity, String> {

    List<KycSubmissionJpaEntity> findByMerchantIdOrderBySubmittedAtDesc(String merchantId);
}
