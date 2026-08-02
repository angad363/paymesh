package com.paymesh.simulator.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * The ambient failure injection settings (SDD 13.1).
 *
 * <h2>Deliberately not idempotency-keyed</h2>
 *
 * Every other write here carries a key; this one does not, because it is last-write-wins
 * configuration rather than an event. A replayed configuration change IS the same configuration, so
 * a key would guard against nothing and would imply the endpoint creates something.
 *
 * <h2>The delay is milliseconds on the wire and a Duration inside</h2>
 *
 * The domain holds a {@link java.time.Duration}; the wire holds an integer, because an ISO-8601
 * duration string is a poor fit for a knob whose realistic range is 0-30000 and whose caller is
 * usually a test. The controller converts. Capped at one hour so a typo cannot park every callback
 * beyond the end of any test run.
 */
public record ConfigureFailureProfileRequest(

    @NotBlank(message = "Default behaviour is required")
    String defaultBehaviour,

    @NotNull(message = "Callback delay is required")
    @PositiveOrZero(message = "Callback delay cannot be negative")
    @Max(value = 3_600_000L, message = "Callback delay must not exceed 3600000 milliseconds")
    Long callbackDelayMs
) {
}
