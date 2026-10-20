package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One row of an intent's timeline: it was in {@code fromStatus}, something moved it to
 * {@code toStatus}, and this is who and why.
 * <p>
 * Append-only. There is no update and no delete, here or in the table -- a history that can be
 * rewritten is not a history.
 *
 * @param fromStatus null for the creation row. An intent that has just been created came from
 *                   nowhere, and repeating the initial status in both columns would claim a
 *                   transition that never happened.
 * @param actorId    which merchant or provider, when there is one to name. SYSTEM actions have no
 *                   principal.
 */
public record PaymentStateChange(
    MerchantId merchantId,
    PaymentIntentId paymentIntentId,
    PaymentIntentStatus fromStatus,
    PaymentIntentStatus toStatus,
    ActorType actorType,
    String actorId,
    String reason,
    Instant occurredAt
) {

    public PaymentStateChange {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        if (paymentIntentId == null) {
            throw new IllegalArgumentException("Payment Intent Identifier cannot be null");
        }

        if (toStatus == null) {
            throw new IllegalArgumentException("Target status cannot be null");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("Actor type cannot be null");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Transition timestamp cannot be null");
        }
    }

    /**
     * Who caused a transition. Mirrors {@code ck_payment_state_history_actor}.
     * <p>
     * Only MERCHANT is reachable today: PROVIDER arrives with callbacks and SYSTEM with the
     * sweepers and reconciliation jobs this design does not build.
     */
    public enum ActorType {
        MERCHANT,
        PROVIDER,
        SYSTEM
    }
}
