package com.paymesh.refund.domain;

import java.util.Locale;

/**
 * What a provider can say about a refund. Two answers, not four.
 * <p>
 * Payment's {@code ProviderOutcome} has AUTHORIZED and REQUIRES_ACTION as well, because a payment
 * is a multi-step conversation -- authorize, challenge, capture. A refund is asked once and
 * answered once: the money came back or it did not. Adding the other two here to look symmetrical
 * would create states nothing can produce and everything downstream would have to handle.
 * <p>
 * This is deliberately NOT Payment's enum. Refund owns its callback route end to end (ADR-019), and
 * importing {@code ProviderOutcome} would put Payment in Refund's import graph for the sake of two
 * constants -- and would silently couple the two contracts, so that adding a payment outcome
 * changed what a refund callback may say.
 */
public enum RefundOutcome {

    SUCCEEDED,
    FAILED;

    public static RefundOutcome parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Refund outcome is required");
        }

        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Refund outcome must be SUCCEEDED or FAILED, got " + value, exception
            );
        }
    }
}
