package com.paymesh.settlement.infrastructure.persistence.jpa;

import com.paymesh.settlement.application.PayoutCallbackRepository;
import com.paymesh.settlement.domain.PayoutId;
import com.paymesh.settlement.domain.PayoutOutcome;

import java.time.Instant;

public final class JpaPayoutCallbackRepository implements PayoutCallbackRepository {

    private final SpringDataPayoutCallbackRepository callbacks;

    public JpaPayoutCallbackRepository(SpringDataPayoutCallbackRepository callbacks) {
        this.callbacks = callbacks;
    }

    @Override
    public boolean recordIfNew(
        String provider,
        String externalEventId,
        PayoutId payoutId,
        PayoutOutcome outcome,
        String payloadHash,
        Instant occurredAt,
        Instant receivedAt
    ) {
        // ONE means this call recorded it; ZERO means the pair was already there. No read, so there
        // is no window between deciding and writing.
        return callbacks.insertIfAbsent(
            provider, externalEventId, payoutId.value(), outcome.name(), payloadHash, occurredAt,
            receivedAt
        ) == 1;
    }
}
