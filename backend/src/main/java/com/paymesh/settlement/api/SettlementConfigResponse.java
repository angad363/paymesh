package com.paymesh.settlement.api;

import com.paymesh.settlement.domain.SettlementConfig;

/**
 * @param holdingPeriodSeconds how long captured funds stay pending. SECONDS as an integer rather
 *     than an ISO-8601 duration string, because every other duration this API exposes is a number
 *     of minor units of something and a caller should not have to parse {@code PT168H} to compare
 *     two of them.
 * @param isDefault true when the merchant has never set one and this is the platform default. A
 *     caller otherwise cannot tell "chosen" from "inherited", and the two behave differently when
 *     the platform default changes.
 */
public record SettlementConfigResponse(long holdingPeriodSeconds, boolean isDefault) {

    public static SettlementConfigResponse from(SettlementConfig config, boolean isDefault) {
        return new SettlementConfigResponse(config.holdingPeriod().toSeconds(), isDefault);
    }
}
