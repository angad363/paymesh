package com.paymesh.refund.application;

/**
 * This refund would take the total past what the payment collected.
 *
 * <h2>IT IS THROWN FROM TWO PLACES, AND THAT IS THE DESIGN</h2>
 *
 * The service checks before inserting, so an ordinary over-refund gets a readable 422 naming both
 * figures. {@code tr_refunds_within_captured} checks again at COMMIT, with the application out of
 * the path, and the adapter translates that violation into this same exception -- which is what
 * catches the concurrent case the service's read cannot see.
 * <p>
 * Same sentence either way, so a caller cannot tell whether they lost a race or simply asked for
 * too much. They do not need to: the answer is the same and so is the remedy.
 */
public final class RefundExceedsCapturedAmountException extends RuntimeException {

    public RefundExceedsCapturedAmountException(
        String paymentIntentId,
        long requestedMinor,
        long alreadySpokenForMinor,
        long capturedMinor
    ) {
        super(
            "A refund of " + requestedMinor + " would take payment intent " + paymentIntentId
                + " to " + (requestedMinor + alreadySpokenForMinor)
                + ", which exceeds the " + capturedMinor + " captured"
        );
    }

    /** For the adapter, which learns only that the trigger fired. */
    public RefundExceedsCapturedAmountException(String paymentIntentId) {
        super("Refunds of payment intent " + paymentIntentId + " would exceed the amount captured");
    }
}
