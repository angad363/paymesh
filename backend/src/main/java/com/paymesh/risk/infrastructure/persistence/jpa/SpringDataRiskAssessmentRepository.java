package com.paymesh.risk.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataRiskAssessmentRepository
    extends JpaRepository<RiskAssessmentJpaEntity, String> {

    /**
     * NO {@code REQUIRES_NEW} HERE, AND THE REASON IS WORTH THE PARAGRAPH.
     *
     * <p>The assessment has to survive a BLOCK, which throws out of the confirm. The first fix was
     * {@code REQUIRES_NEW} on this save, and it worked -- while being the wrong answer. Spring
     * suspends the enclosing transaction WITHOUT releasing its connection, so every confirm would
     * have held two pool connections at once while a row lock was live. With Hikari's default pool
     * of ten and nothing configuring it, about five concurrent confirms wedge the entire
     * application.
     *
     * <p>The right fix was to stop nesting: {@code ConfirmPaymentIntentService} evaluates risk
     * BEFORE it opens its transaction, so this save is enclosed by nothing and Spring Data's own
     * transaction commits it. One connection, and the evidence survives because there is no outer
     * transaction to roll it back.
     */


    /** Newest first, which is what idx_risk_assessments_merchant_recent is built descending for. */
    List<RiskAssessmentJpaEntity> findByMerchantIdOrderByDecidedAtDesc(
        String merchantId, Limit limit
    );
}
