package com.paymesh.simulator.domain;

/**
 * Where a payment stands <b>at the provider</b>.
 * <p>
 * Not PayMesh's {@code PaymentIntentStatus}, and the two must never be read as the same thing. This
 * is what the provider believes; that is what PayMesh has been told and has accepted. The whole
 * point of reconciliation is that they can disagree -- a TIMED_OUT payment here sits against a
 * PROCESSING intent there, and a CAPTURED payment here can sit against a CANCELLED intent (the
 * merchant abandoned a 3DS challenge the customer then completed, ADR-012 section 6).
 */
public enum SimulatedPaymentStatus {

    /** Funds held. Reached by a MANUAL-capture payment, or by an AUTOMATIC one mid-flight. */
    AUTHORIZED,

    /** Collected. */
    CAPTURED,

    /** Refused by the simulated issuer. */
    DECLINED,

    /** Waiting on an off-site customer step. */
    REQUIRES_ACTION,

    /**
     * The provider did something and told nobody. No callback was ever enqueued for it.
     * <p>
     * Deliberately NOT a synonym for DECLINED. A declined payment is a decision the provider
     * communicated; a timed-out one is a decision it did not, which is the strictly worse case and
     * the one PayMesh has no local exit from.
     */
    TIMED_OUT
}
