package com.paymesh.payment.domain;

/**
 * Raised when cancellation is requested from a state that cannot reach CANCELLED.
 * <p>
 * It lives in the domain because the aggregate is what refuses: the rule is the state machine, not
 * a policy the calling service applies. It carries no HTTP status -- the API layer decides that.
 * <p>
 * ({@code java-coding-conventions.md} section 7 says business-rule failures live in
 * {@code application}. That cannot hold for an exception thrown by an aggregate without inverting
 * the dependency direction; {@code OrderNotCancellableException} already resolves it this way.)
 */
public class PaymentIntentNotCancellableException extends RuntimeException {
    public PaymentIntentNotCancellableException(PaymentIntentId paymentIntentId, PaymentIntentStatus status) {
        super("Payment intent " + paymentIntentId.value()
            + " cannot be cancelled while it is " + status.name());
    }
}
