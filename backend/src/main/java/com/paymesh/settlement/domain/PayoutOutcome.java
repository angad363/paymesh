package com.paymesh.settlement.domain;

import java.util.Locale;

/**
 * What a provider said about a payout. PayMesh's vocabulary, not the bank's.
 *
 * <p>The provider's own words are PAID and RETURNED (see {@code ck_provider_payouts_status}); the
 * translation happens at the callback boundary, which is the only place the two dictionaries
 * should meet. Two values, because a payout either moved or it did not -- there is no
 * REQUIRES_ACTION for money leaving a bank.
 */
public enum PayoutOutcome {

    SUCCEEDED,
    FAILED;

    public static PayoutOutcome parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A payout outcome is required");
        }

        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "SUCCEEDED", "PAID" -> SUCCEEDED;
            case "FAILED", "RETURNED" -> FAILED;
            default -> throw new IllegalArgumentException("Unknown payout outcome " + value);
        };
    }
}
