package com.paymesh.settlement.infrastructure.persistence.jpa;

import com.paymesh.settlement.application.PayoutRepository;
import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutId;
import com.paymesh.settlement.domain.PayoutStatus;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JpaPayoutRepository implements PayoutRepository {

    private final SpringDataPayoutRepository payouts;

    public JpaPayoutRepository(SpringDataPayoutRepository payouts) {
        this.payouts = payouts;
    }

    @Override
    public Payout save(Payout payout) {
        payouts.saveAndFlush(toEntity(payout));

        return payout;
    }

    @Override
    public Optional<Payout> find(PayoutId payoutId) {
        return payouts.findById(payoutId.value()).map(JpaPayoutRepository::toDomain);
    }

    @Override
    public Optional<Payout> findByBatch(SettlementBatchId settlementBatchId) {
        return payouts.findBySettlementBatchId(settlementBatchId.value())
            .map(JpaPayoutRepository::toDomain);
    }

    @Override
    public List<String> findDue(Instant now, int limit) {
        return payouts.findDue(now, Limit.of(limit));
    }

    @Override
    public Optional<Payout> findForUpdate(PayoutId payoutId) {
        return payouts.findForUpdate(payoutId.value()).map(JpaPayoutRepository::toDomain);
    }

    private static PayoutJpaEntity toEntity(Payout payout) {
        return new PayoutJpaEntity(
            payout.payoutId().value(),
            payout.settlementBatchId().value(),
            payout.merchantId().value(),
            payout.amountMinor(),
            payout.currency(),
            payout.destination(),
            payout.status().name(),
            payout.attempts(),
            payout.nextAttemptAt(),
            payout.lastError(),
            payout.providerReference(),
            payout.createdAt(),
            payout.updatedAt()
        );
    }

    private static Payout toDomain(PayoutJpaEntity entity) {
        return new Payout(
            PayoutId.from(entity.payoutId()),
            SettlementBatchId.from(entity.settlementBatchId()),
            MerchantId.from(entity.merchantId()),
            entity.amountMinor(),
            entity.currency(),
            entity.destination(),
            PayoutStatus.valueOf(entity.status()),
            entity.attempts(),
            entity.nextAttemptAt(),
            entity.lastError(),
            entity.providerReference(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
