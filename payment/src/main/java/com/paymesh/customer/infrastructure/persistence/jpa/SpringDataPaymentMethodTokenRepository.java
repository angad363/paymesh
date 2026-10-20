package com.paymesh.customer.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataPaymentMethodTokenRepository
    extends JpaRepository<PaymentMethodTokenJpaEntity, String> {

    Optional<PaymentMethodTokenJpaEntity> findByMerchantIdAndPaymentMethodTokenId(
        String merchantId, String paymentMethodTokenId
    );

    /** Live only. A detached card is history, not a payment method. */
    List<PaymentMethodTokenJpaEntity>
        findByMerchantIdAndCustomerIdAndDetachedAtIsNullOrderByCreatedAtDesc(
            String merchantId, String customerId
        );
}
