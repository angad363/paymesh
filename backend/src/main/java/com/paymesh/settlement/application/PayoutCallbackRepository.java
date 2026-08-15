package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.PayoutId;
import com.paymesh.settlement.domain.PayoutOutcome;

import java.time.Instant;

public interface PayoutCallbackRepository {

    /**
     * Records the callback, or reports that it was already recorded.
     *
     * <p>{@code INSERT ... ON CONFLICT DO NOTHING}, and THE ROW COUNT IS THE ANSWER. No read first,
     * so there is no read-then-write window for two deliveries of one event to both pass through --
     * the primary key arbitrates, exactly as {@code processed_events} does for the inbox.
     *
     * @return false when this (provider, event) pair had already been recorded
     */
    boolean recordIfNew(
        String provider,
        String externalEventId,
        PayoutId payoutId,
        PayoutOutcome outcome,
        String payloadHash,
        Instant occurredAt,
        Instant receivedAt
    );
}
