package com.paymesh.order.infrastructure.persistence.jpa;

import com.paymesh.order.application.OrderStateHistoryRepository;
import com.paymesh.order.domain.OrderStateChange;

/**
 * PostgreSQL-backed implementation of the state-history port.
 * <p>
 * There is no {@code @Transactional} here, and its absence is the design. The insert joins whatever
 * transaction the caller already opened, so the timeline row commits with the transition it records
 * or not at all.
 */
public final class JpaOrderStateHistoryRepository implements OrderStateHistoryRepository {

    private final SpringDataOrderStateHistoryRepository history;

    public JpaOrderStateHistoryRepository(SpringDataOrderStateHistoryRepository history) {
        this.history = history;
    }

    /**
     * Flushed rather than left for the commit so a rejected row (an order that does not exist, an
     * actor type outside the CHECK) fails at the line that wrote it, with a stack trace naming the
     * transition, instead of surfacing as an opaque commit-time failure.
     */
    @Override
    public void append(OrderStateChange change) {
        history.saveAndFlush(new OrderStateHistoryJpaEntity(
            change.merchantId().value(),
            change.orderId().value(),
            change.fromStatus() == null ? null : change.fromStatus().name(),
            change.toStatus().name(),
            change.actorType().name(),
            change.actorId(),
            change.reason(),
            change.occurredAt()
        ));
    }
}
