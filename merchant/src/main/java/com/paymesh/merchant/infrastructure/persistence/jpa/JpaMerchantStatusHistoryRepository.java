package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.application.MerchantStatusHistoryRepository;
import com.paymesh.merchant.domain.MerchantStatusChange;

public final class JpaMerchantStatusHistoryRepository implements MerchantStatusHistoryRepository {

    private final SpringDataMerchantStatusHistoryRepository history;

    public JpaMerchantStatusHistoryRepository(SpringDataMerchantStatusHistoryRepository history) {
        this.history = history;
    }

    @Override
    public void append(MerchantStatusChange change) {
        history.save(new MerchantStatusHistoryJpaEntity(
            change.merchantId().value(),
            change.fromStatus() == null ? null : change.fromStatus().name(),
            change.toStatus().name(),
            change.actorType().name(),
            change.actorId(),
            change.reason(),
            change.occurredAt()
        ));
    }
}
