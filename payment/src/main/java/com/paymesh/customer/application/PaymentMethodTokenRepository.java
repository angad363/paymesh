package com.paymesh.customer.application;

import com.paymesh.customer.domain.CustomerId;
import com.paymesh.customer.domain.PaymentMethodToken;
import com.paymesh.customer.domain.PaymentMethodTokenId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

public interface PaymentMethodTokenRepository {

    /**
     * @throws PaymentMethodAlreadyAttachedException when this customer already has a LIVE token
     *     with the same fingerprint. Detected by
     *     {@code uq_payment_method_tokens_live_fingerprint}, not by a pre-read: two concurrent
     *     attaches of one card both find nothing and the partial unique index picks the winner.
     */
    PaymentMethodToken save(PaymentMethodToken token);

    Optional<PaymentMethodToken> findById(MerchantId merchantId, PaymentMethodTokenId tokenId);

    /** Live tokens only. A detached card is history, not a payment method. */
    List<PaymentMethodToken> findLiveByCustomer(MerchantId merchantId, CustomerId customerId);
}
