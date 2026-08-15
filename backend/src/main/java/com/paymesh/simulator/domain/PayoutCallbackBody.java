package com.paymesh.simulator.domain;

import java.time.Instant;

/**
 * The body of one payout callback, field for field as PayMesh's {@code PayoutCallbackRequest}
 * declares it -- <b>restated, never imported</b>, like {@link CallbackBody}.
 *
 * <p>The cost of restating is that the two can drift, and a delivery test is what goes red when
 * they do. That is the notification a shared type would have suppressed, and it is the whole reason
 * the simulator can be deployed away from PayMesh.
 *
 * @param payoutId PayMesh's own payout id, echoed back from {@code externalReference}. The
 *     simulator does not know PayMesh calls it that; it knows this is the field the receiver reads
 */
public record PayoutCallbackBody(
    String eventId,
    Instant occurredAt,
    String payoutId,
    String outcome,
    String failureCode,
    String failureMessage
) {

    public PayoutCallbackBody {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("A payout callback must carry an event identifier");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("A payout callback must carry a timestamp");
        }

        if (payoutId == null || payoutId.isBlank()) {
            throw new IllegalArgumentException("A payout callback must name a payout");
        }

        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("A payout callback must carry an outcome");
        }
    }

    public static PayoutCallbackBody of(String eventId, SimulatedPayout payout, Instant occurredAt) {
        return new PayoutCallbackBody(
            eventId,
            occurredAt,
            payout.externalReference(),
            payout.wasPaid() ? "SUCCEEDED" : "FAILED",
            payout.failureCode(),
            payout.wasPaid() ? null : "The receiving account would not accept the transfer"
        );
    }
}
