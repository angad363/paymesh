package com.paymesh.ledger.api;

import com.paymesh.ledger.application.MerchantBalance;

/**
 * One currency's balance.
 *
 * @param currency ISO 4217, uppercase
 * @param pendingMinor collected, not yet past the holding period. Integer minor units, per the
 *     money convention -- 4000 is 40.00, and there are no decimals anywhere in this API
 * @param availableMinor past the holding period and settleable (ADR-031). <b>A new field, and
 *     adding it is backwards-compatible</b> in a way that changing {@code pendingMinor}'s meaning
 *     would not have been -- which is exactly why {@code MerchantBalance} omitted it rather than
 *     returning zero while there was no release job. May be negative: a refund against funds
 *     already paid out leaves the merchant owing PayMesh.
 */
public record BalanceResponse(String currency, long pendingMinor, long availableMinor) {

    public static BalanceResponse from(MerchantBalance balance) {
        return new BalanceResponse(
            balance.currency(), balance.pendingMinor(), balance.availableMinor()
        );
    }
}
