package com.paymesh.settlement.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param holdingPeriodSeconds zero is allowed -- "release on the next run" is a real policy for a
 *     trusted merchant. Bounded above at one year: a longer holding period is far more likely to be
 *     a units mistake (days typed as seconds) than an intention, and the failure mode of that
 *     mistake is money that never becomes settleable and nobody noticing for months.
 */
public record UpdateSettlementConfigRequest(
    @NotNull
    @Min(0)
    @Max(31_536_000L)
    Long holdingPeriodSeconds
) {
}
