package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntentId;

/**
 * Thrown both when the intent does not exist and when it exists under a different merchant.
 * The two cases are indistinguishable on purpose: telling a merchant "that id is real, just not
 * yours" leaks the existence of another tenant's data.
 */
public class PaymentIntentNotFoundException extends RuntimeException {
    public PaymentIntentNotFoundException(PaymentIntentId paymentIntentId) {
        super("Payment intent not found: " + paymentIntentId.value());
    }
}
