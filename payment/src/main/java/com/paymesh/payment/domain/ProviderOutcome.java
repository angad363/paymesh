package com.paymesh.payment.domain;

import java.util.Locale;

/**
 * What a provider says happened, which is not the same vocabulary as what PayMesh does about it --
 * see {@link ProviderCallbackOutcome} for that.
 * <p>
 * Four values and no more. There is deliberately no CANCELLED: a provider reporting a cancellation
 * is reporting a failure from PayMesh's point of view, and CANCELLED here is reserved for the
 * merchant's own action so that the two can never be confused in an audit.
 */
public enum ProviderOutcome {

    /** Funds held, not moved. The intent stops at AUTHORIZED and waits for a capture. */
    AUTHORIZED,

    /** Collected. The intent reaches SUCCEEDED, which moves no balance (design section 0.5). */
    SUCCEEDED,

    /** Refused. The intent reaches FAILED, which also releases the order's live-intent slot. */
    FAILED,

    /** The customer has an off-site step to complete, typically a 3DS challenge. */
    REQUIRES_ACTION;

    /**
     * Parses the {@code outcome} field of a callback body.
     * <p>
     * {@code valueOf} would do, but its message names the Java enum class, and an error body must
     * not hand a caller internal type names -- even a caller holding the signing secret.
     */
    public static ProviderOutcome parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Provider outcome cannot be null");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown provider outcome: " + value);
        }
    }

    /** The attempt status this outcome puts the try in. The two vocabularies happen to agree. */
    public PaymentAttemptStatus attemptStatus() {
        return PaymentAttemptStatus.valueOf(name());
    }
}
