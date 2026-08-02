package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPaymentId;

/**
 * No such simulated payment.
 * <p>
 * A plain 404 with the id in the message, and no enumeration-oracle argument applies: there is one
 * caller holding one credential, and it necessarily knows which payments it asked for.
 */
public final class SimulatedPaymentNotFoundException extends RuntimeException {

    public SimulatedPaymentNotFoundException(SimulatedPaymentId providerPaymentId) {
        super("No simulated payment with identifier " + providerPaymentId.value());
    }
}
