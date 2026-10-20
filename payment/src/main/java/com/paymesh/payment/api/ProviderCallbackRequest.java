package com.paymesh.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * The body of a provider callback (design section 4.3).
 * <p>
 * Note what is absent, and that it is absent by construction rather than by convention: there is no
 * merchant id, no amount the intent must be changed to, and no status field. <b>The merchant is
 * DERIVED from the intent this body names</b> -- a caller-supplied tenant on an endpoint reachable
 * with one shared secret would be an authorization decision made by the caller. And a provider does
 * not set a status: it reports an outcome, and the aggregate decides whether that outcome is a legal
 * transition from where the intent actually is.
 * <p>
 * The amounts are reported, not commanded. They are checked against the intent's own figure and a
 * mismatch is recorded and refused, never applied -- a provider does not get to change what is owed.
 */
public record ProviderCallbackRequest(

    /** The provider's id for this delivery. Half of the deduplication key; the provider is the other. */
    @NotBlank(message = "eventId is required")
    @Size(max = 120, message = "eventId must not exceed 120 characters")
    String eventId,

    /**
     * The provider's own timestamp, and the value the ordering guard compares. Required, because an
     * event with no time cannot be ordered against the ones already applied, and an unorderable
     * event on this endpoint is one that could drag a payment backwards.
     */
    @NotNull(message = "occurredAt is required")
    Instant occurredAt,

    /**
     * Which intent. Optional only because {@code providerReference} is the other way to resolve one
     * -- a body carrying neither is refused as malformed by {@code ProviderEvent}.
     */
    @Size(max = 40, message = "paymentIntentId must not exceed 40 characters")
    String paymentIntentId,

    @Size(max = 120, message = "providerReference must not exceed 120 characters")
    String providerReference,

    /** AUTHORIZED, SUCCEEDED, FAILED or REQUIRES_ACTION. Parsed, so an unknown value is a 400. */
    @NotBlank(message = "outcome is required")
    String outcome,

    @Positive(message = "authorizedAmountMinor must be a positive number of minor units")
    Long authorizedAmountMinor,

    @Positive(message = "capturedAmountMinor must be a positive number of minor units")
    Long capturedAmountMinor,

    @Size(max = 60, message = "failureCode must not exceed 60 characters")
    String failureCode,

    @Size(max = 500, message = "failureMessage must not exceed 500 characters")
    String failureMessage,

    /**
     * Where to send the customer for an off-site step. Stored REDACTED -- the origin and path
     * survive and the query string does not, because a 3DS challenge URL carries a token in it.
     */
    @Size(max = 500, message = "actionUrl must not exceed 500 characters")
    String actionUrl
) {
}
