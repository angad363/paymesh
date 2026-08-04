package com.paymesh.simulator.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One request to send money back out.
 * <p>
 * No callback is enqueued for this and none should be: {@code /internal/v1/provider-callbacks}
 * speaks only the four payment outcomes, so a refund callback today would retry into a 404 until it
 * was ABANDONED. The row is the provider's truth and appears in the reconciliation export.
 */
public record CreateSimulatedRefundRequest(

    @NotBlank(message = "Idempotency key is required")
    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    String idempotencyKey,

    @NotBlank(message = "Provider payment id is required")
    @Size(max = 50, message = "Provider payment id must not exceed 50 characters")
    String providerPaymentId,

    /**
     * The caller's own reference for this refund, echoed back on the row and in the reconciliation
     * export (ADR-026). PayMesh puts its refund id here. OPTIONAL, because a real provider does not
     * refuse a refund for want of a merchant's bookkeeping string -- it just cannot be matched
     * later, which the export then reports as unmatched.
     */
    @Size(max = 120, message = "Callback reference must not exceed 120 characters")
    String callbackReference,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be a positive number of minor units")
    @Max(value = 999_999_999_999L, message = "Amount must not exceed 999999999999 minor units")
    Long amountMinor
) {
}
