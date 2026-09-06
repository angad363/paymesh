package com.paymesh.simulator.domain;

/** A capture was asked for more than the provider is holding. */
public final class CaptureExceedsAuthorizedAmountException extends RuntimeException {

    public CaptureExceedsAuthorizedAmountException(
        SimulatedPaymentId providerPaymentId,
        long requestedAmountMinor,
        long authorizedAmountMinor
    ) {
        super(
            "Simulated payment " + providerPaymentId.value() + " authorized "
                + authorizedAmountMinor + " and cannot capture " + requestedAmountMinor
        );
    }
}
