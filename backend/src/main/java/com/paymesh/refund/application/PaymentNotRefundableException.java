package com.paymesh.refund.application;

/**
 * The payment cannot be refunded.
 *
 * <h2>THREE CAUSES, ONE ANSWER, ON PURPOSE</h2>
 *
 * No such payment intent; a payment intent belonging to another merchant; a payment that never
 * collected anything. All three produce this exception with the same message, so nothing is learned
 * from the difference. It is the same shape as Payment's own {@code ORDER_NOT_PAYABLE}, which
 * covers "no such order", "not yours" and "not PENDING" with one code for exactly this reason.
 */
public final class PaymentNotRefundableException extends RuntimeException {

    public PaymentNotRefundableException(String paymentIntentId) {
        super("Payment intent " + paymentIntentId + " cannot be refunded");
    }
}
