package com.paymesh.simulator.domain;

/**
 * A refund was asked for more than is left to give back.
 * <p>
 * The advisory half of a three-part guard: this message is what makes the answer readable, the
 * application takes a row lock so concurrent refunds cannot both pass it, and
 * {@code ck_provider_payments_refunded} in V13 is the one that cannot be bypassed.
 */
public final class RefundExceedsCapturedAmountException extends RuntimeException {

    public RefundExceedsCapturedAmountException(
        SimulatedPaymentId providerPaymentId,
        long requestedAmountMinor,
        long refundableAmountMinor
    ) {
        super(
            "Simulated payment " + providerPaymentId.value() + " has " + refundableAmountMinor
                + " left to refund and cannot refund " + requestedAmountMinor
        );
    }
}
