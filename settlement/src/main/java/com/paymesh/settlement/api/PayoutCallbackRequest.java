package com.paymesh.settlement.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * What a payout provider sends. The external contract, deliberately not a domain type.
 *
 * @param eventId the PROVIDER's event id, and what deduplication keys on together with the provider
 *     name. Not PayMesh's -- a provider that retries sends the same one
 * @param outcome SUCCEEDED or FAILED in PayMesh's words, or PAID/RETURNED in a bank's. Both parse,
 *     because insisting a provider learn this platform's vocabulary is not a contract, it is a
 *     translation cost pushed onto the wrong side
 */
public record PayoutCallbackRequest(
    @NotBlank String eventId,
    @NotNull Instant occurredAt,
    @NotBlank String payoutId,
    @NotBlank String outcome,
    String failureCode,
    String failureMessage
) {
}
