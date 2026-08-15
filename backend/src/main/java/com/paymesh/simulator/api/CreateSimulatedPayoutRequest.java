package com.paymesh.simulator.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param externalReference the caller's own id, and the idempotency key. Required, because a
 *     provider that cannot deduplicate is a provider nobody can safely retry against
 * @param destination {@code acct_sim_fail} is returned by the bank; anything else is paid.
 *     Deterministic on purpose -- see {@code SimulatedPayout}
 */
public record CreateSimulatedPayoutRequest(
    @NotBlank @Size(max = 60) String externalReference,
    @NotBlank @Size(max = 80) String destination,
    @Positive long amountMinor,
    @NotBlank @Size(min = 3, max = 3) String currency
) {
}
