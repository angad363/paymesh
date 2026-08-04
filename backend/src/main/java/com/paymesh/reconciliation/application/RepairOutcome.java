package com.paymesh.reconciliation.application;

/**
 * What replaying one provider row actually did.
 *
 * <h2>THE JOB NEVER DECIDES THIS, AND THAT IS THE DESIGN</h2>
 *
 * A reconciliation job is the obvious place to put "compare the provider's status to ours and work
 * out what to change". This one deliberately does not: it replays every terminal provider row
 * through the SAME callback entry point a real provider callback goes through, and the payment or
 * refund aggregate answers. That answer is what these values report.
 * <p>
 * The reason is that a second copy of the transition rules would rot. {@code PaymentIntent} already
 * refuses AUTHORIZED to SUCCEEDED from a callback, already refuses an amount the intent does not
 * authorize, already judges staleness before the state machine, and the Ledger already posts from
 * the event that results. A job that re-derived any of that would be a second state machine that
 * nobody updates when the first one changes, on the money path.
 */
public enum RepairOutcome {

    /**
     * PayMesh disagreed with the provider and has been corrected. <b>The interesting one.</b> Every
     * occurrence is a divergence that existed until this run -- most often a payment ADR-015's
     * sweeper timed out to FAILED that the provider had in fact collected, whose balance the Ledger
     * has now posted because the repair emitted the same event a callback would have.
     */
    REPAIRED,

    /**
     * PayMesh already agreed, or the aggregate refused the replay as stale, terminal or duplicate.
     * The overwhelmingly common outcome on a healthy platform, and the reason a daily re-run of an
     * old day is cheap and safe rather than something to guard against.
     */
    ALREADY_CONSISTENT,

    /**
     * The row names nothing PayMesh recognises: a null reference, or a payment that was never
     * created here. Reported, never repaired -- inventing a local record from a provider's row would
     * be manufacturing money movement out of a file.
     */
    UNRESOLVED,

    /**
     * The replay threw. Counted rather than propagated so one bad row cannot disable the pass, which
     * is the failure shape open item 2 records in two other sweeps.
     */
    ERRORED
}
