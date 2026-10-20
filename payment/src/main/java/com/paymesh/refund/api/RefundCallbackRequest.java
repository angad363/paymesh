package com.paymesh.refund.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The refund callback wire contract. REFUND'S OWN, not a reuse of Payment's (ADR-019).
 * <p>
 * It looks similar because both describe a provider reporting an outcome, and it is separate
 * because the two contracts must be free to move independently -- adding a payment outcome must not
 * change what a refund callback may say.
 */
public record RefundCallbackRequest(

    @NotBlank(message = "Event identifier is required")
    @Size(max = 100, message = "Event identifier must be at most 100 characters")
    String eventId,

    @NotNull(message = "Occurrence instant is required")
    Instant occurredAt,

    @NotBlank(message = "Refund identifier is required")
    @Pattern(
        regexp = "^ref_[0-9a-fA-F-]{36}$",
        message = "Refund identifier must be a ref_ prefixed UUID"
    )
    String refundId,

    @Size(max = 100, message = "Provider reference must be at most 100 characters")
    String providerReference,

    @NotBlank(message = "Outcome is required")
    String outcome,

    @Size(max = 64, message = "Failure code must be at most 64 characters")
    String failureCode,

    @Size(max = 500, message = "Failure message must be at most 500 characters")
    String failureMessage
) {
}
