package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedRefund;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The port the simulator's refund storage answers. Unscoped, for the reason stated on payments. */
public interface SimulatedRefundRepository {

    SimulatedRefund save(SimulatedRefund refund);

    Optional<SimulatedRefund> findByIdempotencyKey(String idempotencyKey);

    List<SimulatedRefund> findCreatedBetween(Instant fromInclusive, Instant toExclusive);
}
