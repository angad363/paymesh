package com.paymesh.risk.domain;

/**
 * What Risk tells Payment to do. SDD §14.
 *
 * <h2>THREE VALUES, NOT THE FOUR SDD §14 LISTS</h2>
 *
 * The SDD names {@code ALLOW}, {@code REQUIRE_ACTION}, {@code REVIEW} and {@code BLOCK}.
 * {@code REQUIRE_ACTION} means "step the customer up to 3DS", and PayMesh has no step-up to
 * trigger: the simulator's {@code tok_sim_3ds} path is driven by the token the merchant sends, not
 * by anything Risk could ask for. A constant nothing can produce and nothing can act on is dead
 * flexibility, and this codebase has spent two ADRs (021, 024, 027) making unreachable enum
 * constants reachable. Better to add it with the mechanism than to ship the label now.
 *
 * <h2>AND {@code REVIEW} IS NOT A SECOND SPELLING OF {@code ALLOW}, EVEN THOUGH BOTH PROCEED</h2>
 *
 * There is no analyst queue yet (the plan cut it: nothing reads one), so a {@code REVIEW} payment
 * is confirmed exactly like an {@code ALLOW} payment. The difference is the evidence: a
 * {@code REVIEW} row says "this was let through and here is what looked wrong about it", which is
 * a question an operator can ask of `risk_assessments` today. That is worth a constant.
 * <p>
 * <b>Do not make REVIEW block.</b> With nothing working a queue, a blocking REVIEW strands the
 * payment forever, which is a worse outcome than the risk it was hedging.
 */
public enum RiskOutcome {

    /** Nothing matched. Confirm proceeds. */
    ALLOW,

    /** Something matched that is worth a human's attention later. Confirm still proceeds. */
    REVIEW,

    /** Confirm is refused. The only outcome that changes what Payment does. */
    BLOCK;

    /** Whether Payment may proceed with the confirm. The one question Payment asks of this. */
    public boolean permitsConfirmation() {
        return this != BLOCK;
    }
}
