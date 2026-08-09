package com.paymesh.risk.infrastructure.payment;

import com.paymesh.payment.infrastructure.persistence.jpa.PaymentIntentJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

/**
 * The one query Risk makes of Payment's table.
 * <p>
 * Served by {@code idx_payment_intents_merchant_customer_created}, which V27 adds for exactly this
 * -- see that migration for why a scan here would be an outage rather than a slow query.
 */
public interface SpringDataPaymentIntentCounter
    extends JpaRepository<PaymentIntentJpaEntity, String> {

    long countByMerchantIdAndCustomerIdAndCreatedAtGreaterThanEqual(
        String merchantId, String customerId, Instant createdAt
    );
}
