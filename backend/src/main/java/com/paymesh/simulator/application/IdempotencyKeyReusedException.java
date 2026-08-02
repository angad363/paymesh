package com.paymesh.simulator.application;

/**
 * The same provider-side idempotency key arrived with a different request.
 * <p>
 * <b>409, not a replay of the original.</b> Real providers differ here and returning the original
 * would be the friendlier choice -- but the original may be for a different amount, and answering
 * "your payment for 5000 succeeded" to a request for 50000 is a lie on the money path. ADR-009
 * reached the same conclusion for the platform's own idempotency layer, and for the same reason:
 * failing closed on a spurious conflict is strictly safer than replaying the wrong answer.
 */
public final class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(String idempotencyKey) {
        super(
            "Idempotency key " + idempotencyKey
                + " has already been used for a different request"
        );
    }
}
