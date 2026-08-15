package com.paymesh.simulator.domain;

/**
 * The BANK's vocabulary, not PayMesh's.
 *
 * <p>A bank says a transfer was paid or that it came back; it does not say SUCCEEDED or FAILED. The
 * translation happens at PayMesh's callback boundary, which is the only place the two dictionaries
 * should meet -- the same reason {@code SimulatedOutcome} restates rather than imports
 * {@code ProviderOutcome}.
 */
public enum SimulatedPayoutStatus {

    PAID,
    RETURNED
}
