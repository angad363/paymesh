package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPayment;

/**
 * A created payment, and whether it was created just now.
 *
 * @param replayed true when an existing payment was returned for a repeated idempotency key. The
 *                 controller turns it into 200 rather than 201, so a caller can tell a replay from a
 *                 create without diffing bodies
 */
public record SimulatedPaymentResult(SimulatedPayment payment, boolean replayed) {

    static SimulatedPaymentResult created(SimulatedPayment payment) {
        return new SimulatedPaymentResult(payment, false);
    }

    static SimulatedPaymentResult replayed(SimulatedPayment payment) {
        return new SimulatedPaymentResult(payment, true);
    }
}
