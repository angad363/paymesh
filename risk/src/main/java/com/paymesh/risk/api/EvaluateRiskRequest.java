package com.paymesh.risk.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * The wire shape of a risk evaluation request. Payment's {@code RiskServiceHttpCheck} builds this
 * body from facts it already holds at confirm time, exactly what {@code EvaluateRiskCommand}
 * expects in-process (ADR-044 section 2) -- this is that command, crossing a wire.
 *
 * @param customerId the customer, or null on a guest checkout. Null is meaningful, not missing --
 *     see {@code EvaluateRiskCommand}'s own javadoc.
 * @param device     the opaque client hint from the confirm request, or null.
 */
public record EvaluateRiskRequest(
    @NotBlank String merchantId,
    @NotBlank String paymentIntentId,
    @Positive long amountMinor,
    @NotBlank String currency,
    String customerId,
    String device
) {
}
