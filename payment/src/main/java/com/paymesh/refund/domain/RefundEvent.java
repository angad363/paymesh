package com.paymesh.refund.domain;

import java.time.Instant;

/**
 * What a provider told us about a refund, normalized off the wire.
 * <p>
 * The API record it comes from is a wire contract; this is the domain's reading of it. The
 * separation is what lets the provider's spelling change without the state machine noticing.
 *
 * @param externalEventId the provider's own event id, the dedup key
 * @param occurredAt the provider's clock, used to refuse a stale callback that overtakes a newer one
 */
public record RefundEvent(
    String externalEventId,
    Instant occurredAt,
    String refundId,
    String providerReference,
    RefundOutcome outcome,
    String failureCode,
    String failureMessage
) {

    public RefundEvent {
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new IllegalArgumentException("A refund callback must carry an event identifier");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("A refund callback must carry an instant");
        }

        if (refundId == null || refundId.isBlank()) {
            throw new IllegalArgumentException("A refund callback must name a refund");
        }

        if (outcome == null) {
            throw new IllegalArgumentException("A refund callback must carry an outcome");
        }
    }
}
