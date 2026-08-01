package com.paymesh.payment.application;

/**
 * The order already holds a live payment intent (ADR-011).
 * <p>
 * Raised by the persistence adapter when {@code uq_payment_intents_live_per_order} refuses the
 * insert, and by the create service's pre-check before it gets that far. The pre-check exists only
 * for the friendlier message; the index is the guarantee, because two concurrent creates can both
 * pass a check and only one can win a unique index.
 * <p>
 * The slot is released when the live intent reaches FAILED or CANCELLED.
 */
public class OrderHasActivePaymentIntentException extends RuntimeException {
    public OrderHasActivePaymentIntentException(String orderId) {
        super("Order " + orderId + " already has an active payment intent");
    }
}
