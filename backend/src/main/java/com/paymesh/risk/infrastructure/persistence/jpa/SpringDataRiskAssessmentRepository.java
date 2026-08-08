package com.paymesh.risk.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataRiskAssessmentRepository
    extends JpaRepository<RiskAssessmentJpaEntity, String> {

    /** Newest first, which is what idx_risk_assessments_merchant_recent is built descending for. */
    List<RiskAssessmentJpaEntity> findByMerchantIdOrderByDecidedAtDesc(
        String merchantId, Limit limit
    );
}
