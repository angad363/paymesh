package com.paymesh.payment.domain;

/**
 * Raised when a manual capture is requested and the intent cannot serve it.
 * <p>
 * ONE EXCEPTION FOR TWO CAUSES -- the wrong state, and the wrong capture method -- because both are
 * the same answer to the caller: this intent is not one you capture, and retrying the identical
 * request will never change that. They are distinguished in the MESSAGE rather than in the type, so
 * a merchant reading the error learns which it was without the API growing a second code for a
 * conflict that has one remedy.
 * <p>
 * It is not the enumeration-oracle case that forced {@code ORDER_NOT_PAYABLE} to blur three causes:
 * the caller has already proved they own this intent by resolving it, so neither message reveals
 * anything they could not read from a GET.
 * <p>
 * It lives in the domain because the aggregate is what refuses: the rule is the state machine, not
 * a policy the calling service applies. It carries no HTTP status -- the API layer decides that.
 */
public class PaymentIntentNotCapturableException extends RuntimeException {

    /** The intent is not AUTHORIZED, so there is no held authorization to collect. */
    public PaymentIntentNotCapturableException(
        PaymentIntentId paymentIntentId,
        PaymentIntentStatus status
    ) {
        super("Payment intent " + paymentIntentId.value()
            + " cannot be captured while it is " + status.name());
    }

    /**
     * The intent is AUTHORIZED but captures automatically, so the provider's SUCCEEDED callback
     * collects it and a manual capture would be a second collector racing the first.
     */
    public PaymentIntentNotCapturableException(
        PaymentIntentId paymentIntentId,
        CaptureMethod captureMethod
    ) {
        super("Payment intent " + paymentIntentId.value()
            + " has capture method " + captureMethod.name()
            + " and is captured by the provider, not by the merchant");
    }
}
