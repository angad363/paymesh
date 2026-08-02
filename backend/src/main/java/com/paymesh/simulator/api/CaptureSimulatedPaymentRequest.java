package com.paymesh.simulator.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One request to settle an authorization.
 * <p>
 * {@code amountMinor} is optional and boxed: absent means "all of it", which is what a caller that
 * simply wants the authorized amount means, and a primitive could not express that. A present zero
 * is a range error rather than a synonym for absent.
 * <p>
 * The idempotency key is accepted and validated so the contract is uniform across the three writes,
 * but capture does not need it to be safe: {@code SELECT ... FOR UPDATE} plus the aggregate's
 * refusal to capture anything that is not AUTHORIZED already makes a second capture a 409 rather
 * than a second collection.
 */
public record CaptureSimulatedPaymentRequest(

    @NotBlank(message = "Idempotency key is required")
    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    String idempotencyKey,

    @Positive(message = "Amount must be a positive number of minor units")
    @Max(value = 999_999_999_999L, message = "Amount must not exceed 999999999999 minor units")
    Long amountMinor
) {
}
