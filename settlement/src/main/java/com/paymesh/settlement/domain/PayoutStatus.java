package com.paymesh.settlement.domain;

/**
 * Where a payout is. Mirrors {@code ck_payouts_status}.
 *
 * <p>No CANCELLED. A merchant cannot cancel a payout of their own money to their own account, and
 * PayMesh cancelling one after submission would be an opinion about a bank movement it cannot see
 * -- the same argument ADR-019 makes for a refund cancellation answering 409.
 */
public enum PayoutStatus {

    /** Created alongside its batch, not yet submitted. */
    PENDING,

    /** The provider has it. The answer arrives as a callback. */
    SUBMITTED,

    PAID,

    FAILED;

    public boolean isTerminal() {
        return this == PAID || this == FAILED;
    }
}
