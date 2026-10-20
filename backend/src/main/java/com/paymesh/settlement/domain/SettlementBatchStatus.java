package com.paymesh.settlement.domain;

/**
 * Where a batch is. Mirrors {@code ck_settlement_batches_status} exactly.
 *
 * <p>There is no OPEN and no CLOSED: a batch is cut complete, because the balance it describes has
 * already moved into the in-transit account by the time the row exists. A state meaning "still
 * accumulating" would be a state no row is ever in, and this codebase has spent three ADRs making
 * unreachable enum values reachable.
 */
public enum SettlementBatchStatus {

    /** Cut by Settlement; the Ledger has not yet confirmed it posted. No payout may be submitted. */
    PENDING_PAYOUT,

    /**
     * The Ledger posted the cut ({@code ledger.batch_posted}): available is now in-transit and the
     * payout may be submitted. Until here a stale cut can still be rejected, and a payout already
     * paid out by the provider could not be taken back.
     */
    POSTED,

    /** The provider confirmed the payout landed. Terminal. */
    PAID,

    /** The payout failed terminally and the funds went back to available. Terminal. */
    RETURNED
}
