package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

public final class GetPaymentIntentService {

    private final PaymentIntentRepository paymentIntents;

    public GetPaymentIntentService(PaymentIntentRepository paymentIntents) {
        this.paymentIntents = paymentIntents;
    }

    /**
     * The merchantId argument is the authorization, not a filter: an intent belonging to another
     * merchant is reported as not found, so a {@code pi_} in a path never proves the caller may read
     * the row.
     */
    public PaymentIntent getById(MerchantId merchantId, PaymentIntentId paymentIntentId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        if (paymentIntentId == null) {
            throw new IllegalArgumentException("Payment Intent ID cannot be null");
        }

        return paymentIntents
            .findByPaymentIntentId(merchantId, paymentIntentId)
            .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
    }
}
