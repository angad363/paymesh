package com.paymesh.simulator.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One request asking the provider to take a payment.
 *
 * <h2>The idempotency key is in the BODY, not in a header, and that is deliberate</h2>
 *
 * Every other write in this codebase carries {@code Idempotency-Key} as a header, because
 * {@code IdempotencyFilter} reads it there. That filter is not in this path and must not be: it keys
 * on {@code merchant + endpoint + key} and the merchant comes from a verified bearer token, which a
 * provider does not have. This module runs its own mechanism against
 * {@code uq_provider_payments_idempotency_key}, so the key is an ordinary field of the request the
 * service hashes -- and putting it in the header would invite exactly the confusion of looking like
 * the platform layer while being something else.
 *
 * <h2>What is absent</h2>
 *
 * No merchant, no order, no payment intent. The simulator has never been told those things exist.
 * {@code callbackReference} is the caller's own opaque string, echoed back and never interpreted --
 * PayMesh happens to put a payment intent id there.
 * <p>
 * {@code amountMinor} is a boxed Long so "absent" and "zero" stay distinguishable; a primitive would
 * default a missing amount to 0 and report a required field as a range error.
 * <p>
 * {@code method} and {@code captureMethod} are bound as Strings and parsed by the domain rather than
 * by Jackson, so an unknown value produces the module's own message instead of one naming a Java
 * enum class.
 */
public record CreateSimulatedPaymentRequest(

    @NotBlank(message = "Idempotency key is required")
    @Size(max = 120, message = "Idempotency key must not exceed 120 characters")
    String idempotencyKey,

    @NotBlank(message = "Callback reference is required")
    @Size(max = 60, message = "Callback reference must not exceed 60 characters")
    String callbackReference,

    @NotBlank(message = "Method is required")
    @Pattern(
        regexp = "^\\s*(?i:CARD|UPI|WALLET|BANK)\\s*$",
        message = "Method must be CARD, UPI, WALLET or BANK"
    )
    String method,

    @NotBlank(message = "Token is required")
    @Size(max = 60, message = "Token must not exceed 60 characters")
    String token,

    @NotNull(message = "Amount is required")
    @Positive(message = "Amount must be a positive number of minor units")
    @Max(value = 999_999_999_999L, message = "Amount must not exceed 999999999999 minor units")
    Long amountMinor,

    @NotBlank(message = "Currency is required")
    @Pattern(
        regexp = "^\\s*[A-Za-z]{3}\\s*$",
        message = "Currency must be a three-letter ISO 4217 code"
    )
    String currency,

    /** Optional; AUTOMATIC when absent, which is what a caller that just wants its money means. */
    @Pattern(
        regexp = "^\\s*(?i:AUTOMATIC|MANUAL)\\s*$",
        message = "Capture method must be AUTOMATIC or MANUAL"
    )
    String captureMethod
) {
}
