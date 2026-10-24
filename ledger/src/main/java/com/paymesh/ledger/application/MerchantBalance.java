package com.paymesh.ledger.application;

/**
 * What PayMesh owes one merchant in one currency, in minor units.
 *
 * <h2>THREE FIGURES NOW, NOT SDD 15.3's FOUR</h2>
 *
 * {@code GET /v1/balances} is specified to return "pending, available, reserved and
 * in-settlement". {@code available} arrived with V29 and {@code ReleaseAvailableFundsService}
 * (ADR-031) -- <b>this is the backwards-compatible addition the previous version of this javadoc
 * predicted</b>, and it is why the field was omitted rather than returned as zero. A zero
 * {@code availableMinor} would have been a claim: it says the merchant has nothing to withdraw,
 * when the truth was that "available" was not a concept this ledger had.
 * <p>
 * {@code inSettlement} arrived the same way with V32: Settlement cuts a batch, the funds move to
 * {@code SETTLEMENT_IN_TRANSIT}, and without this figure they would simply disappear from the
 * merchant's balance for as long as the payout took. <b>Only {@code reserved} is still omitted</b>,
 * because nothing holds funds -- and it stays omitted rather than zero for the reason above.
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
 * @param inSettlementMinor committed to a batch and on its way to the merchant's bank. Not
 *     settleable again -- that is the whole point of the account (SDD 17.6 invariant 2) -- and not
 *     yet gone, so a payout that fails terminally has somewhere to come back from
 */
public record MerchantBalance(
    String currency, long pendingMinor, long availableMinor, long inSettlementMinor
) {
    /** Nothing in settlement -- for callers (and pre-settlement tests) that never cut a batch. */
    public MerchantBalance(String currency, long pendingMinor, long availableMinor) {
        this(currency, pendingMinor, availableMinor, 0L);
    }
}
