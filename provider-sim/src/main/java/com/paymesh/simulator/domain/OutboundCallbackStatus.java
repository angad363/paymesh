package com.paymesh.simulator.domain;

/**
 * Where one intended callback stands.
 * <p>
 * There is deliberately no FAILED. A delivery that failed is one that will be retried, and PENDING
 * already says that; a separate FAILED would be a state the dispatcher had to remember to look at
 * and would eventually stop looking at.
 */
public enum OutboundCallbackStatus {

    /** Due, or waiting for {@code deliver_after}. The dispatcher's only input. */
    PENDING,

    /** PayMesh answered 2xx. What it answered is in {@code last_response_outcome}. */
    DELIVERED,

    /**
     * Gave up after {@code maxAttempts}. A real provider's retry budget is finite, and a row that
     * retried forever against a 401 would be an infinite loop wearing the costume of resilience.
     */
    ABANDONED
}
