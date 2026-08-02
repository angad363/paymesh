package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedCaptureMethod;
import com.paymesh.simulator.domain.SimulatedMethod;

/**
 * One request to take a payment, parsed.
 *
 * @param callbackReference the CALLER's own reference, echoed into every callback and never
 *                          interpreted. PayMesh puts its payment intent id here; the simulator does
 *                          not know that is what it is
 * @param token             a deterministic test token (SDD 13.6), never anything derived from a real
 *                          instrument
 */
public record CreateSimulatedPaymentCommand(
    String idempotencyKey,
    String callbackReference,
    SimulatedMethod method,
    String token,
    long amountMinor,
    String currency,
    SimulatedCaptureMethod captureMethod
) {
}
