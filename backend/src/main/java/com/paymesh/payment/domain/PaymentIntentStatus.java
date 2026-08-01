package com.paymesh.payment.domain;

import java.util.Locale;

/**
 * The payment intent lifecycle (SDD 12.1).
 * <pre>
 * REQUIRES_PAYMENT_METHOD --attach--&gt; REQUIRES_CONFIRMATION --confirm--&gt; PROCESSING
 *        |                                    |                              |
 *        |                                    |            SUCCEEDED | FAILED | REQUIRES_ACTION | AUTHORIZED
 *        +-------------cancel-----------------+-------------cancel-----------+
 *                                                                            |
 *                                                                        CANCELLED
 *
 * SUCCEEDED --&gt; PARTIALLY_REFUNDED --&gt; REFUNDED   (the Refund capability; not this design)
 * </pre>
 * All ten are declared here and in {@code ck_payment_intents_status} so that no later PR needs a
 * migration to widen the column -- the precedent {@code OrderStatus} set. <b>Only
 * REQUIRES_PAYMENT_METHOD and CANCELLED are reachable today</b>; attach and confirm arrive with the
 * next PR and the provider-driven states with the one after. No code path may reach a state before
 * the PR that owns it.
 */
public enum PaymentIntentStatus {
    REQUIRES_PAYMENT_METHOD,
    REQUIRES_CONFIRMATION,
    PROCESSING,
    REQUIRES_ACTION,
    AUTHORIZED,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    /**
     * Parses a caller-supplied status filter.
     * <p>
     * {@code valueOf} would do, but its message names the Java enum class, and an error body must
     * not hand a caller internal type names.
     */
    public static PaymentIntentStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Payment intent status cannot be null");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown payment intent status: " + value);
        }
    }
}
