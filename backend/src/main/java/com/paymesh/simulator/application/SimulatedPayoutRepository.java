package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPayout;

import java.util.Optional;

public interface SimulatedPayoutRepository {

    SimulatedPayout save(SimulatedPayout payout);

    /** The idempotency read: a resubmission returns the original rather than paying twice. */
    Optional<SimulatedPayout> findByExternalReference(String externalReference);
}
