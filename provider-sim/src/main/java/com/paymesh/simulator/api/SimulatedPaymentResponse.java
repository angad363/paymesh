package com.paymesh.simulator.api;

import com.paymesh.simulator.domain.SimulatedPayment;

import java.time.Instant;

/**
 * The external shape of a provider payment.
 * <p>
 * {@code behaviour} is exposed on purpose, and it is the one field a real provider would never
 * return. This module exists to be driven by a test, and a test that cannot see which behaviour a
 * token resolved to has to infer it from callbacks that may not have been dispatched yet -- which
 * turns a one-line assertion into a poll.
 * <p>
 * {@code requestHash} and {@code idempotencyKey} are NOT exposed. The hash is an internal dedup
 * detail, and echoing the key back tells a caller nothing it did not just send.
 */
public record SimulatedPaymentResponse(
    String providerPaymentId,
    String callbackReference,
    String method,
    String behaviour,
    String status,
    long amountMinor,
    String currency,
    String captureMethod,
    long capturedAmountMinor,
    long refundedAmountMinor,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt
) {

    public static SimulatedPaymentResponse from(SimulatedPayment payment) {
        return new SimulatedPaymentResponse(
            payment.providerPaymentId().value(),
            payment.callbackReference(),
            payment.method().name(),
            payment.behaviour().name(),
            payment.status().name(),
            payment.amountMinor(),
            payment.currency(),
            payment.captureMethod().name(),
            payment.capturedAmountMinor(),
            payment.refundedAmountMinor(),
            payment.failureCode(),
            payment.failureMessage(),
            payment.createdAt(),
            payment.updatedAt()
        );
    }
}
