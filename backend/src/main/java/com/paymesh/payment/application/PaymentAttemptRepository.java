package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Writes to an intent's attempts.
 * <p>
 * Two methods and no finder: nothing reads an attempt back yet -- no endpoint exposes one and no
 * provider answers -- and a query with no caller would be a guess about what the callback PR wants.
 * <p>
 * Like {@code OutboxWriter}, {@code append} assumes a transaction is already open and must not
 * start one. An attempt that committed while the transition that opened it rolled back would be a
 * collection nobody asked for.
 */
public interface PaymentAttemptRepository {

    /**
     * One past the highest attempt number this intent already has; 1 for an intent that has never
     * been confirmed.
     * <p>
     * A CHECK, NOT A LOCK, exactly like {@code existsLiveForOrder}. Two concurrent confirms both
     * read the same count and both try to write the same number;
     * {@code uq_payment_attempts_intent_number} is what makes the second one lose.
     */
    int nextAttemptNumber(MerchantId merchantId, PaymentIntentId paymentIntentId);

    void append(PaymentAttempt attempt);
}
