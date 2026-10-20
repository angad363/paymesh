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

    /**
     * The provider-callback resolution path, where the intent was named by a provider's own
     * reference rather than by a PayMesh id.
     * <p>
     * The leak argument does not apply here and the answer is still 404, for a different reason: the
     * caller is the provider, it necessarily knows which references it issued, and the 404 is a
     * deliberate request to RETRY -- the likeliest cause is a callback overtaking the transaction
     * that created the attempt (design section 4.1).
     */
    public PaymentIntentNotFoundException(String provider, String providerReference) {
        super("No payment intent is known for " + provider + " reference " + providerReference);
    }
}
