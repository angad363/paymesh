package com.paymesh.simulator.domain;

/**
 * What the provider tells PayMesh happened -- <b>the receiver's vocabulary, restated here rather
 * than imported.</b>
 * <p>
 * These four names are exactly {@code com.paymesh.payment.domain.ProviderOutcome}'s, and importing
 * that enum would be one line, would compile, and would delete the module boundary. SDD 13.6 wants
 * the simulator <b>independently deployable</b> so that network failure between it and PayMesh is
 * realistic; a shared type makes the two one deployable by definition, and
 * {@code ModuleBoundaryTest} exists to catch the moment someone reaches for it.
 * <p>
 * The duplication is the contract being PUBLISHED rather than SHARED -- what a separate service
 * reading an OpenAPI document would have. It also buys a notification a shared type would have
 * suppressed: if PayMesh changes the contract, this module's integration test goes red rather than
 * silently recompiling into a different meaning.
 * <p>
 * There is deliberately no CANCELLED. PayMesh reserves that for the merchant's own action, so a
 * provider reporting a cancellation reports {@link #FAILED}.
 */
public enum SimulatedOutcome {

    /** Funds held, not moved. Drives a PayMesh intent to AUTHORIZED, awaiting a manual capture. */
    AUTHORIZED,

    /** Collected. Drives a PayMesh intent to SUCCEEDED. */
    SUCCEEDED,

    /** Refused. Drives a PayMesh intent to FAILED and releases the order's live-intent slot. */
    FAILED,

    /** The customer has an off-site step, typically a 3DS challenge. */
    REQUIRES_ACTION
}
