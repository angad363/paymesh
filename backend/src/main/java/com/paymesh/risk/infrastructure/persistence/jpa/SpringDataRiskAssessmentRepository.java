package com.paymesh.risk.infrastructure.persistence.jpa;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SpringDataRiskAssessmentRepository
    extends JpaRepository<RiskAssessmentJpaEntity, String> {

    /**
     * ITS OWN TRANSACTION, AND THE INTEGRATION TEST IS WHAT PROVED THIS IS NECESSARY.
     *
     * <p>Risk is evaluated inside {@code ConfirmPaymentIntentService}'s transaction, and a BLOCK
     * makes that transaction throw. Enlisting in it meant the evidence rolled back with the confirm
     * it refused: the merchant got a 422 naming an assessment id, and the row that id pointed at did
     * not exist. Exactly the outcome the whole capability exists to prevent.
     *
     * <p>So the assessment commits independently, the same shape the idempotency record uses for the
     * same reason -- write it and commit it BEFORE the thing it is about is allowed to fail.
     *
     * <p><b>What that means, stated plainly:</b> an assessment records that an evaluation happened,
     * not that the payment proceeded. A confirm that fails afterwards for an unrelated reason still
     * leaves one. That is the correct reading -- evaluating IS the event being recorded -- and it is
     * why the row carries no payment status.
     *
     * <p>{@code REQUIRES_NEW} sits here rather than on the adapter because the adapter is a final
     * class (the convention for hand-wired beans) and Spring cannot CGLIB-proxy a final class, so
     * the annotation there would silently do nothing. An interface is JDK-proxied. Same note as
     * {@code SpringDataApiCredentialRepository.touchLastUsed}.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    <S extends RiskAssessmentJpaEntity> S save(S entity);

    /** Newest first, which is what idx_risk_assessments_merchant_recent is built descending for. */
    List<RiskAssessmentJpaEntity> findByMerchantIdOrderByDecidedAtDesc(
        String merchantId, Limit limit
    );
}
