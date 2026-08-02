package com.paymesh.ledger.api;

import com.paymesh.ledger.application.MerchantBalance;

/**
 * One currency's balance.
 *
 * @param currency ISO 4217, uppercase
 * @param pendingMinor integer minor units, per the money convention -- 4000 is 40.00, and there are
 *     no decimals anywhere in this API
 */
public record BalanceResponse(String currency, long pendingMinor) {

    public static BalanceResponse from(MerchantBalance balance) {
        return new BalanceResponse(balance.currency(), balance.pendingMinor());
    }
}
