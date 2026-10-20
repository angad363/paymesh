package com.paymesh.payment.domain;

/**
 * One attempt's own lifecycle, which is NOT the intent's.
 * <p>
 * The vocabularies overlap and are not the same, which is why this is a separate enum rather than a
 * reuse of {@link PaymentIntentStatus}. An attempt starts at PROCESSING and only a provider moves
 * it, so the intent's pre-provider states have no meaning here -- and neither does CANCELLED: a
 * merchant giving up locally says nothing about what the provider did with the attempt, and putting
 * that value in this column would be a claim PayMesh cannot make. Sharing the intent's enum would
 * have made all five of those spellable.
 * <p>
 * Mirrors {@code ck_payment_attempts_status}. <b>Only PROCESSING is reachable today</b>; the other
 * four arrive with provider callbacks, and no code path may reach a state before the PR that owns
 * it.
 */
public enum PaymentAttemptStatus {
    PROCESSING,
    REQUIRES_ACTION,
    AUTHORIZED,
    SUCCEEDED,
    FAILED
}
