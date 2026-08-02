package com.paymesh.simulator.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The ambient failure injection settings (SDD 13.1), of which there is exactly one row.
 * <p>
 * A record rather than an aggregate, because it has no state machine: it is a configuration value
 * that is read and replaced. The singleton-ness is enforced by
 * {@code ck_provider_failure_profile_singleton} in V13, not by convention here.
 *
 * @param defaultBehaviour what a payment gets when its token names no behaviour. The token wins
 *                         where it names one -- see {@link SimulatedBehaviour}
 * @param callbackDelay    added to every enqueued callback's {@code deliver_after}. Zero still means
 *                         asynchronous: this knob controls how late a callback is, not whether it
 *                         goes through the dispatcher
 */
public record FailureProfile(
    SimulatedBehaviour defaultBehaviour,
    Duration callbackDelay,
    Instant updatedAt
) {

    /** The literal the CHECK constraint pins. There is one row and this is its name. */
    public static final String PROFILE_ID = "DEFAULT";

    public FailureProfile {
        if (defaultBehaviour == null) {
            throw new IllegalArgumentException("A default behaviour is required");
        }

        if (callbackDelay == null || callbackDelay.isNegative()) {
            throw new IllegalArgumentException("Callback delay cannot be negative");
        }

        if (updatedAt == null) {
            throw new IllegalArgumentException("An update timestamp is required");
        }
    }
}
