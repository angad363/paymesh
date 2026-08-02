package com.paymesh.ledger.api;

import com.paymesh.ledger.application.MerchantBalance;

import java.util.List;

/**
 * Wrapped in an object rather than returned as a bare JSON array.
 * <p>
 * A top-level array cannot gain a sibling field without breaking every client that parses it, and
 * this response has obvious ones coming -- {@code asOf} once anybody reconciles against it, and the
 * available/reserved/in-settlement figures once Settlement exists. The wrapper costs one level of
 * nesting now and saves a breaking change later.
 * <p>
 * Not paginated: the list is one row per currency a merchant has been paid in, which is bounded by
 * the currencies they trade in rather than by their volume.
 */
public record BalanceListResponse(List<BalanceResponse> balances) {

    public static BalanceListResponse from(List<MerchantBalance> balances) {
        return new BalanceListResponse(balances.stream().map(BalanceResponse::from).toList());
    }
}
