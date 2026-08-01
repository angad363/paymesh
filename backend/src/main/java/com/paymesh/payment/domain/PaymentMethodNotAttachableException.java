package com.paymesh.payment.domain;

/**
 * Raised when a payment method is attached to an intent that is past the point of choosing one.
 * <p>
 * It lives in the domain because the aggregate is what refuses: the rule is the state machine, not
 * a policy the calling service applies. It carries no HTTP status -- the API layer decides that.
 * <p>
 * It names the state the intent is actually in, which is safe: the caller already owns the intent
 * and could read the same status from a GET.
 */
public class PaymentMethodNotAttachableException extends RuntimeException {
    public PaymentMethodNotAttachableException(PaymentIntentId paymentIntentId, PaymentIntentStatus status) {
        super("Payment intent " + paymentIntentId.value()
            + " cannot take a payment method while it is " + status.name());
    }
}
