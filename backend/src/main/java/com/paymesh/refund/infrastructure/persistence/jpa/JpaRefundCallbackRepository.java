package com.paymesh.refund.infrastructure.persistence.jpa;

import com.paymesh.refund.application.RefundCallbackRepository;
import com.paymesh.refund.domain.RefundEvent;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

public final class JpaRefundCallbackRepository implements RefundCallbackRepository {

    private final SpringDataRefundCallbackRepository callbacks;

    public JpaRefundCallbackRepository(SpringDataRefundCallbackRepository callbacks) {
        this.callbacks = callbacks;
    }

    /**
     * THE INSERT IS THE CLAIM. A read-then-write would let two concurrent deliveries of one
     * callback both find nothing and both apply it; here the second loses on
     * {@code pk_refund_callbacks} and is reported as a duplicate.
     * <p>
     * The pre-check is kept for the ordinary case, where a redelivery is answered without raising
     * anything -- but it is the catch below that is load-bearing.
     */
    @Override
    public boolean record(String provider, RefundEvent event, String payloadHash, Instant receivedAt) {
        if (callbacks.existsByProviderAndExternalEventId(provider, event.externalEventId())) {
            return false;
        }

        try {
            callbacks.saveAndFlush(new RefundCallbackJpaEntity(
                provider,
                event.externalEventId(),
                event.refundId(),
                event.outcome().name(),
                payloadHash,
                event.occurredAt(),
                receivedAt
            ));

            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    @Override
    public Optional<Instant> latestAppliedAt(String refundId) {
        return callbacks.latestOccurredAt(refundId);
    }
}
