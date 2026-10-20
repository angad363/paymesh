package com.paymesh.refund.domain;

/**
 * A refund's lifecycle.
 *
 * <pre>
 *   PENDING --submit--&gt; PROCESSING --provider says--&gt; SUCCEEDED
 *      |                     |
 *      |                     +--provider says--------&gt; FAILED
 *      |
 *      +--cancel----------------------------------&gt; CANCELLED
 * </pre>
 *
 * <h2>WHICH OF THESE COUNT AGAINST THE CAPTURED AMOUNT</h2>
 *
 * Everything except {@link #FAILED} and {@link #CANCELLED}. A PENDING or PROCESSING refund has
 * moved no money yet, but the provider may be about to, so it is spoken for. Counting only
 * SUCCEEDED would let a merchant queue ten full refunds of one payment while the first is in
 * flight, each individually valid. {@code tr_refunds_within_captured} encodes exactly this list,
 * and {@link #countsAgainstCapturedAmount()} is the Java side of the same sentence -- if the two
 * ever disagree, the database wins and the application is the one that is wrong.
 */
public enum RefundStatus {

    /** Accepted by PayMesh, not yet handed to the provider. The only cancellable state. */
    PENDING,

    /** With the provider. Only a callback moves it from here. */
    PROCESSING,

    /** The provider returned the money. Terminal. */
    SUCCEEDED,

    /** The provider refused. Terminal, and the amount stops being spoken for. */
    FAILED,

    /** Withdrawn before the provider saw it. Terminal, and no money ever moved. */
    CANCELLED;

    /** Mirrors {@code tr_refunds_within_captured}'s {@code NOT IN ('FAILED','CANCELLED')}. */
    public boolean countsAgainstCapturedAmount() {
        return this != FAILED && this != CANCELLED;
    }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
