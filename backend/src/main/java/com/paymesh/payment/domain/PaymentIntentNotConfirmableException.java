package com.paymesh.payment.domain;

/**
 * Raised when confirmation is requested from a state that cannot reach PROCESSING.
 * <p>
 * Most often that state is REQUIRES_PAYMENT_METHOD: an intent with nothing attached has no
 * instrument to be collected with, and this refusal is what makes attach a genuine prerequisite
 * rather than a convention.
 * <p>
 * It lives in the domain because the aggregate is what refuses. It carries no HTTP status -- the
 * API layer decides that.
 */
public class PaymentIntentNotConfirmableException extends RuntimeException {
    public PaymentIntentNotConfirmableException(PaymentIntentId paymentIntentId, PaymentIntentStatus status) {
        super("Payment intent " + paymentIntentId.value()
            + " cannot be confirmed while it is " + status.name());
    }
}
