package com.paymesh.risk.application;

import com.paymesh.shared.tenant.MerchantId;

/**
 * The input to "evaluate this payment".
 * <p>
 * Everything here is a fact the caller already holds at confirm time, so evaluation adds no read of
 * the payment intent it is judging -- Payment has the row locked and can simply say what is on it.
 *
 * @param customerId the customer, or null on a guest checkout. Null is meaningful, not missing.
 * @param device     the opaque client hint from the confirm request, or null.
 */
public record EvaluateRiskCommand(
    MerchantId merchantId,
    String paymentIntentId,
    long amountMinor,
    String currency,
    String customerId,
    String device
) {

    public EvaluateRiskCommand {
        if (merchantId == null) {
            throw new IllegalArgumentException("Evaluate Risk merchant cannot be null");
        }

        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("Evaluate Risk payment intent cannot be blank");
        }
    }
}
