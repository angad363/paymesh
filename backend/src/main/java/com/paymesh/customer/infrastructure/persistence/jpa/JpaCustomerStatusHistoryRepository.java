package com.paymesh.customer.infrastructure.persistence.jpa;

import com.paymesh.customer.application.CustomerStatusHistoryRepository;
import com.paymesh.customer.domain.CustomerStatusChange;

public final class JpaCustomerStatusHistoryRepository implements CustomerStatusHistoryRepository {

    private final SpringDataCustomerStatusHistoryRepository history;

    public JpaCustomerStatusHistoryRepository(SpringDataCustomerStatusHistoryRepository history) {
        this.history = history;
    }

    @Override
    public void append(CustomerStatusChange change) {
        history.save(new CustomerStatusHistoryJpaEntity(
            change.merchantId().value(),
            change.customerId().value(),
            change.fromStatus() == null ? null : change.fromStatus().name(),
            change.toStatus().name(),
            change.actorType().name(),
            change.actorId(),
            change.reason(),
            change.occurredAt()
        ));
    }
}
