package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPaymentId;

/** One request to send money back. */
public record CreateSimulatedRefundCommand(
    String idempotencyKey,
    SimulatedPaymentId providerPaymentId,
    long amountMinor
) {
}
