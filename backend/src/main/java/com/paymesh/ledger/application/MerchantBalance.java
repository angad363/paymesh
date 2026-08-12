package com.paymesh.ledger.application;

/**
 * What PayMesh owes one merchant in one currency, in minor units.
 *
 * <h2>TWO FIGURES NOW, NOT SDD 15.3's FOUR</h2>
 *
 * {@code GET /v1/balances} is specified to return "pending, available, reserved and
 * in-settlement". {@code available} arrived with V29 and {@code ReleaseAvailableFundsService}
 * (ADR-031) -- <b>this is the backwards-compatible addition the previous version of this javadoc
 * predicted</b>, and it is why the field was omitted rather than returned as zero. A zero
 * {@code availableMinor} would have been a claim: it says the merchant has nothing to withdraw,
 * when the truth was that "available" was not a concept this ledger had.
 * <p>
 * The remaining two are still omitted for the same reason: {@code reserved} needs balance holds and
 * {@code inSettlement} needs Settlement (PR 4). Adding them later stays backwards-compatible;
 * reversing the meaning of a field that has been reading zero would not.
 *
 * @param currency ISO 4217, uppercase
 * @param pendingMinor collected, past no holding period yet, not withdrawable. Signed, because a
 *     refund reversal drives it down and can legitimately cross zero.
 * @param availableMinor collected, past the merchant's holding period, and settleable. <b>Also
 *     signed, and that is not defensive typing.</b> A refund against a payment whose funds have
 *     already been released debits this account, and if the merchant has since been paid out there
 *     may be nothing here to debit -- a negative available balance is PayMesh being owed money by
 *     the merchant, which is a real state a payment platform has to be able to represent rather
 *     than a number to clamp at zero.
 */
public record MerchantBalance(String currency, long pendingMinor, long availableMinor) {
}
