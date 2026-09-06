package com.paymesh.simulator.infrastructure.persistence.jpa;

import com.paymesh.simulator.application.SimulatedPayoutRepository;
import com.paymesh.simulator.domain.SimulatedPayout;
import com.paymesh.simulator.domain.SimulatedPayoutId;
import com.paymesh.simulator.domain.SimulatedPayoutStatus;

import java.util.Optional;

public final class JpaSimulatedPayoutRepository implements SimulatedPayoutRepository {

    private final SpringDataSimulatedPayoutRepository payouts;

    public JpaSimulatedPayoutRepository(SpringDataSimulatedPayoutRepository payouts) {
        this.payouts = payouts;
    }

    @Override
    public SimulatedPayout save(SimulatedPayout payout) {
        payouts.saveAndFlush(new SimulatedPayoutJpaEntity(
            payout.providerPayoutId().value(),
            payout.externalReference(),
            payout.destination(),
            payout.amountMinor(),
            payout.currency(),
            payout.status().name(),
            payout.failureCode(),
            payout.createdAt(),
            payout.updatedAt()
        ));

        return payout;
    }

    @Override
    public Optional<SimulatedPayout> findByExternalReference(String externalReference) {
        return payouts.findByExternalReference(externalReference).map(entity -> new SimulatedPayout(
            SimulatedPayoutId.from(entity.providerPayoutId()),
            entity.externalReference(),
            entity.destination(),
            entity.amountMinor(),
            entity.currency(),
            SimulatedPayoutStatus.valueOf(entity.status()),
            entity.failureCode(),
            entity.createdAt(),
            entity.updatedAt()
        ));
    }
}
