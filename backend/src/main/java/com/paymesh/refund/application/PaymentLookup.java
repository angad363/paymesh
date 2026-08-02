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

    /**
     * The same read, with a ROW LOCK on the payment intent held until the caller's transaction
     * commits.
     *
     * <h2>THIS IS WHAT ACTUALLY STOPS A CONCURRENT OVER-REFUND, AND THE TRIGGER IS NOT</h2>
     *
     * {@code tr_refunds_within_captured} was written believing it settled the race. It does not,
     * and the reason is worth stating precisely because it is easy to get wrong twice: a DEFERRED
     * constraint trigger fires at COMMIT, but the query inside it runs on the snapshot of the
     * STATEMENT that queued it -- not a fresh one. So when two transactions each insert a full
     * refund, the second one's trigger looks at the database as it was before the first committed,
     * sees only its own row, and passes. Both commit. {@code RefundConcurrencyTest} demonstrated
     * exactly that before this method existed.
     * <p>
     * Locking the payment intent makes the two transactions take turns: the loser blocks here until
     * the winner commits, then reads a total that includes the winner's refund and is refused by
     * the ordinary pre-check with a readable message.
     * <p>
     * <b>The trigger stays</b>, and is still worth having -- it is the guard against a raw INSERT,
     * a migration, or a future caller that forgets to lock, none of which take this path at all.
     * It is a backstop, not the mechanism.
     */
    Optional<RefundablePayment> findRefundableForUpdate(MerchantId merchantId, String paymentIntentId);
}
