package com.paymesh.webhook.domain;

/**
 * Where one (event, endpoint) delivery has got to.
 *
 * <p>{@code FAILED} means PayMesh gave up after the retry budget, not that the merchant rejected
 * the payload -- a 4xx and a connection refused both land here. It is terminal only in the sense
 * that the dispatcher will not pick it up again; a replay creates work against the same row.
 */
public enum DeliveryStatus {

    /** Queued, with a {@code next_attempt_at}. The only state the dispatcher claims. */
    PENDING,

    /** The endpoint answered 2xx. */
    DELIVERED,

    /** The retry budget is spent. Recoverable only by an explicit replay. */
    FAILED
}
