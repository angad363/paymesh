package com.paymesh.simulator.api;

import com.paymesh.simulator.domain.FailureProfile;

import java.time.Instant;

/** The ambient failure injection settings as they now stand. */
public record FailureProfileResponse(
    String defaultBehaviour,
    long callbackDelayMs,
    Instant updatedAt
) {

    public static FailureProfileResponse from(FailureProfile profile) {
        return new FailureProfileResponse(
            profile.defaultBehaviour().name(),
            profile.callbackDelay().toMillis(),
            profile.updatedAt()
        );
    }
}
