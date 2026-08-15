package com.paymesh.settlement.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param holdingPeriodSeconds zero is allowed -- "release on the next run" is a real policy for a
 *     trusted merchant. Bounded above at one year: a longer holding period is far more likely to be
 *     a units mistake (days typed as seconds) than an intention, and the failure mode of that
 *     mistake is money that never becomes settleable and nobody noticing for months.
 * @param payoutDestination where payouts go, or null for "not configured". <b>PUT replaces rather
 *     than merges</b>, so omitting this clears it -- which is the only reading of PUT that does not
 *     require a client to know what it did not send. A merchant with no destination is never
 *     batched
 * @param minimumPayoutMinor below this a batch is not worth cutting. Null means the platform
 *     default of 1, which is "any positive balance". Zero is refused rather than treated as "no
 *     minimum": a zero-value payout is not a policy
 */
public record UpdateSettlementConfigRequest(
    @NotNull
    @Min(0)
    @Max(31_536_000L)
    Long holdingPeriodSeconds,

    @Size(max = 80)
    String payoutDestination,

    @Min(1)
    Long minimumPayoutMinor
) {
}
