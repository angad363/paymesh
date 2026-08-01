package com.paymesh.payment.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access to the payment_attempts table.
 * <p>
 * findById(String) is inherited from JpaRepository but is NOT used by the adapter and must not be:
 * it resolves an attempt by id alone, with no tenant predicate. Every method declared here names
 * merchantId first, so the generated SQL always carries "where merchant_id = ?".
 */
public interface SpringDataPaymentAttemptRepository
    extends JpaRepository<PaymentAttemptJpaEntity, String> {

    long countByMerchantIdAndPaymentIntentId(String merchantId, String paymentIntentId);
}
