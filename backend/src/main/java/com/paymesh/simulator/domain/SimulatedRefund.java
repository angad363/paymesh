package com.paymesh.simulator.domain;

import java.time.Instant;

/**
 * The provider's record of money going back out.
 * <p>
 * <b>Enqueues no callback, deliberately.</b> {@code /internal/v1/provider-callbacks} speaks only the
 * four payment outcomes, and PayMesh's Refund capability -- with whatever receiver it brings -- lands
 * later. A refund callback today would be a row that can only retry into a 404 and end ABANDONED.
 * The row is the provider's truth and it appears in the reconciliation export; the dispatcher gains
 * a refund row type in the PR that builds the receiver.
 */
public record SimulatedRefund(
    SimulatedRefundId providerRefundId,
    SimulatedPaymentId providerPaymentId,
    /**
     * The CALLER's own opaque string, echoed back and never interpreted (ADR-026). The simulator has
     * no idea PayMesh puts a refund id here; it knows only that this is the field the caller will
     * want to read its own row back by. Exactly what {@code callbackReference} already is on
     * {@link SimulatedPayment}.
     * <p>
     * Nullable, and it stays that way: a caller that supplies nothing gets a row it cannot resolve,
     * which is its problem to notice in the export rather than an error the provider invents.
     */
    String callbackReference,
    String idempotencyKey,
    String requestHash,
    long amountMinor,
    RefundStatus status,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt
) {

    public static final String DECLINE_CODE = "refund_declined";
    public static final String DECLINE_MESSAGE = "The issuer declined the refund.";

    public SimulatedRefund {
        if (providerRefundId == null || providerPaymentId == null) {
            throw new IllegalArgumentException("A refund must name a payment and itself");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency key cannot be blank");
        }

        if (requestHash == null || requestHash.length() != 64) {
            throw new IllegalArgumentException("Request hash must be 64 hexadecimal characters");
        }

        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be a positive number of minor units");
        }

        if (status == null) {
            throw new IllegalArgumentException("A refund status is required");
        }

        if (createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("A refund must be timestamped");
        }
    }

    /**
     * Takes the refund and decides, in one place, whether the simulated issuer honoured it.
     * <p>
     * DECLINE is driven by the ambient failure profile rather than by a token, because a refund
     * request carries no instrument -- there is nothing on it for a token to be attached to. That
     * makes "make refunds fail" a single profile change, which is what a Refund capability under
     * test will actually want.
     */
    public static SimulatedRefund start(
        SimulatedRefundId providerRefundId,
        SimulatedPaymentId providerPaymentId,
        String callbackReference,
        String idempotencyKey,
        String requestHash,
        long amountMinor,
        SimulatedBehaviour ambientBehaviour,
        Instant now
    ) {
        boolean declined = ambientBehaviour == SimulatedBehaviour.DECLINE;

        return new SimulatedRefund(
            providerRefundId,
            providerPaymentId,
            callbackReference,
            idempotencyKey,
            requestHash,
            amountMinor,
            declined ? RefundStatus.FAILED : RefundStatus.SUCCEEDED,
            declined ? DECLINE_CODE : null,
            declined ? DECLINE_MESSAGE : null,
            now,
            now
        );
    }

    /** A declined refund moves no money, so it must not consume the payment's refundable balance. */
    public boolean movedMoney() {
        return status == RefundStatus.SUCCEEDED;
    }
}
