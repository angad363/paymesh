package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.domain.PaymentStateChange;

/**
 * PostgreSQL-backed implementation of the state-history port.
 * <p>
 * There is no {@code @Transactional} here, and its absence is the design. The insert joins whatever
 * transaction the caller already opened, so the timeline row commits with the transition it records
 * or not at all.
 */
public final class JpaPaymentStateHistoryRepository implements PaymentStateHistoryRepository {

    private final SpringDataPaymentStateHistoryRepository history;

    public JpaPaymentStateHistoryRepository(SpringDataPaymentStateHistoryRepository history) {
        this.history = history;
    }

    /**
     * Flushed rather than left for the commit so a rejected row (an intent that does not exist, an
     * actor type outside the CHECK) fails at the line that wrote it, with a stack trace naming the
     * transition, instead of surfacing as an opaque commit-time failure.
     */
    @Override
    public void append(PaymentStateChange change) {
        history.saveAndFlush(new PaymentStateHistoryJpaEntity(
            change.merchantId().value(),
            change.paymentIntentId().value(),
            change.fromStatus() == null ? null : change.fromStatus().name(),
            change.toStatus().name(),
            change.actorType().name(),
            change.actorId(),
            change.reason(),
            change.occurredAt()
        ));
    }
}
