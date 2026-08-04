package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPaymentId;

/** One request to send money back. */
public record CreateSimulatedRefundCommand(
    String idempotencyKey,
    SimulatedPaymentId providerPaymentId,
    /** The caller's own reference, echoed back. Null is permitted; see {@code SimulatedRefund}. */
    String callbackReference,
    long amountMinor
) {
}
