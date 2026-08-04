package com.paymesh.simulator.api;

import com.paymesh.simulator.domain.SimulatedRefund;

import java.time.Instant;

/** The external shape of a provider refund. */
public record SimulatedRefundResponse(
    String providerRefundId,
    String providerPaymentId,
    /** The caller's own reference. The only field on this row the caller can resolve back. */
    String callbackReference,
    long amountMinor,
    String status,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt
) {

    public static SimulatedRefundResponse from(SimulatedRefund refund) {
        return new SimulatedRefundResponse(
            refund.providerRefundId().value(),
            refund.providerPaymentId().value(),
            refund.callbackReference(),
            refund.amountMinor(),
            refund.status().name(),
            refund.failureCode(),
            refund.failureMessage(),
            refund.createdAt(),
            refund.updatedAt()
        );
    }
}
