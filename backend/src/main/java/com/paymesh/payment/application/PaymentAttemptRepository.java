package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Optional;

/**
 * Reads and writes to an intent's attempts.
 * <p>
 * Like {@code OutboxWriter}, every method here assumes a transaction is already open and must not
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

    /** The most recent try -- the one a provider callback is about. */
    Optional<PaymentAttempt> findLatest(MerchantId merchantId, PaymentIntentId paymentIntentId);

    /**
     * The attempt a provider named by its own reference, with no merchant supplied.
     * <p>
     * The fallback resolution path for a callback that does not carry a {@code paymentIntentId},
     * which is the normal case for a real provider. Unscoped for the same reason
     * {@code PaymentIntentRepository.findForProviderCallbackForUpdate} is: the merchant is derived
     * from the row, never supplied by the caller. {@code uq_payment_attempts_provider_reference} is
     * what makes the answer unique.
     */
    Optional<PaymentAttempt> findByProviderReference(String provider, String providerReference);

    /**
     * THE MONOTONIC EVENT CLOCK, READ ACROSS ALL OF AN INTENT'S ATTEMPTS. Empty when no provider
     * event has ever been applied to this intent.
     * <p>
     * <b>Per intent, not per attempt, and the difference is the whole point</b> (ADR-012). The
     * column lives on {@code payment_attempts} because that is where V9 put it and because it also
     * records which try the provider last spoke about -- but the cycle the clock exists to police,
     * PROCESSING to REQUIRES_ACTION and back, <em>spans two attempts</em>: re-confirming opens a new
     * one. A clock read from the latest attempt alone would be null on that new attempt and would
     * wave through the stale REQUIRES_ACTION event it exists to refuse, which is the exact bug the
     * state machine cannot catch on its own. The maximum across the intent's attempts is the value
     * that is actually monotonic.
     */
    Optional<Instant> lastProviderEventAt(MerchantId merchantId, PaymentIntentId paymentIntentId);

    /** Persists a moved attempt. Optimistically locked, like the intent. */
    void save(PaymentAttempt attempt);
}
