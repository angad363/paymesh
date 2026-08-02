package com.paymesh.ledger.application;

/**
 * What PayMesh owes one merchant in one currency, in minor units.
 *
 * <h2>ONE FIGURE, NOT SDD 15.3's FOUR</h2>
 *
 * {@code GET /v1/balances} is specified to return "pending, available, reserved and
 * in-settlement". Three of those describe money that has moved somewhere this platform cannot yet
 * move it: {@code available} needs a settlement schedule to release pending funds,
 * {@code reserved} needs balance holds, {@code inSettlement} needs Settlement itself. All three are
 * Phase 2.
 * <p>
 * They are omitted rather than returned as zero. A zero {@code availableMinor} is a claim -- it
 * says this merchant has nothing available to withdraw, when the truth is that "available" is not a
 * concept this ledger has. Omitting the field says that instead, and adding it later is a
 * backwards-compatible change; reversing the meaning of a field that has been reading zero is not.
 *
 * @param currency ISO 4217, uppercase
 * @param pendingMinor collected and not yet paid out. Never negative in practice today -- only
 *     captures post, and they only credit -- but typed as a signed long because a reversal
 *     transaction can legitimately drive it down, and Refund will.
 */
public record MerchantBalance(String currency, long pendingMinor) {
}
