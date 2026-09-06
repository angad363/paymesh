package com.paymesh.webhook.domain;

/**
 * Whether PayMesh is still trying to reach this endpoint.
 *
 * <p>Only two states, deliberately. A third for "failing but not yet given up" would be a second
 * record of what {@code consecutive_failures} already says, and two records of one fact can
 * disagree -- the same argument that kept {@code account_balances} out of V15.
 */
public enum EndpointStatus {

    /** Deliveries are queued and sent. */
    ACTIVE,

    /**
     * Queued deliveries stay {@code PENDING} and the dispatcher skips them, so re-enabling resumes
     * rather than replays. Reached automatically after the consecutive-failure threshold, or by the
     * merchant. Reversible either way.
     */
    DISABLED
}
