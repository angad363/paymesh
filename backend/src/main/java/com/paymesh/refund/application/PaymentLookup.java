package com.paymesh.refund.application;

import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * What Refund needs to know about a payment before it will refund one. A PORT OWNED BY THE
 * CONSUMER (ADR-008), implemented in {@code refund.infrastructure.payment}.
 *
 * <h2>WHY REFUND MAY IMPORT PAYMENT WHEN ORDER MAY NOT</h2>
 *
 * {@code ModuleBoundaryTest} gives Order an EMPTY allowlist against Payment, because Payment
 * already reads Order and the second arrow would make the pair cyclic -- neither extractable
 * without the other. Refund is not in that position: nothing in PayMesh imports Refund, so an
 * adapter here leaves the graph acyclic and Refund remains the leaf.
 * <p>
 * So this follows the {@code OrderLookup} shape rather than the {@code PaymentActivityLookup} one:
 * the interface is Refund's, the adapter is Refund's, and the allowlist permits exactly one
 * directory.
 *
 * <h2>It returns a snapshot, not a PaymentIntent</h2>
 *
 * {@link RefundablePayment} is Refund's own record with the four facts it needs. Returning
 * Payment's aggregate would hand Refund the whole state machine and every rule attached to it, and
 * the adapter is then the only place that has to change when Payment's shape does.
 */
public interface PaymentLookup {

    /**
     * Empty when there is no such payment intent OR it belongs to another merchant. Deliberately
     * the same answer for both -- distinguishing them tells a caller that somebody else's payment
     * exists, which is the enumeration oracle the whole codebase avoids.
     */
    Optional<RefundablePayment> findRefundable(MerchantId merchantId, String paymentIntentId);
}
