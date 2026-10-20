package com.paymesh.refund.infrastructure.persistence.jpa;

import com.paymesh.refund.application.RefundStateHistoryRepository;
import com.paymesh.refund.domain.RefundStateChange;

public final class JpaRefundStateHistoryRepository implements RefundStateHistoryRepository {

    private final SpringDataRefundStateHistoryRepository history;

    public JpaRefundStateHistoryRepository(SpringDataRefundStateHistoryRepository history) {
        this.history = history;
    }

    @Override
    public void append(RefundStateChange change) {
        history.save(new RefundStateHistoryJpaEntity(
            change.merchantId().value(),
            change.refundId().value(),
            change.fromStatus() == null ? null : change.fromStatus().name(),
            change.toStatus().name(),
            change.actorType().name(),
            change.actorId(),
            change.reason(),
            change.occurredAt()
        ));
    }
}
