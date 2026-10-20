package com.paymesh.payment.domain;

/**
 * A refund succeeded against a payment intent that is in no state to record one.
 * <p>
 * Not a caller's error -- nothing reaches this through the API. It is raised by Payment's consumer
 * of {@code refund.succeeded} when the event describes money going back against a payment that,
 * according to Payment, never collected it or has already returned all of it. That is a
 * reconciliation problem between two modules, and the consumer logs and moves on rather than
 * retrying forever against a fact that will not change.
 */
public final class PaymentIntentNotRefundableException extends IllegalStateException {

    private final PaymentIntentId paymentIntentId;
    private final PaymentIntentStatus status;

    public PaymentIntentNotRefundableException(
        PaymentIntentId paymentIntentId,
        PaymentIntentStatus status
    ) {
        super(
            "Payment intent " + paymentIntentId.value() + " is " + status
                + " and cannot record a refund"
        );

        this.paymentIntentId = paymentIntentId;
        this.status = status;
    }

    public PaymentIntentId paymentIntentId() {
        return paymentIntentId;
    }

    public PaymentIntentStatus status() {
        return status;
    }
}
