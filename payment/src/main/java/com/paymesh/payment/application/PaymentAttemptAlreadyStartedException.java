package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntentId;

/**
 * The intent already has an attempt with this number -- two confirms raced and this one lost.
 * <p>
 * Raised by the persistence adapter when {@code uq_payment_attempts_intent_number} refuses the
 * insert. There is no application pre-check to disguise it as: the next attempt number is counted,
 * and a count is a check rather than a lock, so the constraint is the only thing that arbitrates.
 * <p>
 * The realistic cause is a double-clicked confirm on a fresh idempotency key. 409 rather than 500,
 * because the caller's request was refused for a reason they can act on: the collection they asked
 * for is already under way.
 */
public class PaymentAttemptAlreadyStartedException extends RuntimeException {
    public PaymentAttemptAlreadyStartedException(PaymentIntentId paymentIntentId) {
        super("Payment intent " + paymentIntentId.value() + " already has an attempt in flight");
    }
}
