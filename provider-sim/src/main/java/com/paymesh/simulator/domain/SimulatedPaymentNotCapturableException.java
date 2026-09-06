package com.paymesh.simulator.domain;

/**
 * Capture was requested from a state that is not AUTHORIZED.
 * <p>
 * In {@code domain} rather than {@code application} because the aggregate throws it, and an
 * aggregate that imported an application type would invert the dependency direction. This is the
 * exception to {@code java-coding-conventions.md} section 7 that open item 15 already records.
 */
public final class SimulatedPaymentNotCapturableException extends RuntimeException {

    private final transient SimulatedPaymentId providerPaymentId;
    private final SimulatedPaymentStatus status;

    public SimulatedPaymentNotCapturableException(
        SimulatedPaymentId providerPaymentId,
        SimulatedPaymentStatus status
    ) {
        super(
            "Simulated payment " + providerPaymentId.value() + " cannot be captured from "
                + status.name()
        );
        this.providerPaymentId = providerPaymentId;
        this.status = status;
    }

    public SimulatedPaymentId providerPaymentId() {
        return providerPaymentId;
    }

    public SimulatedPaymentStatus status() {
        return status;
    }
}
