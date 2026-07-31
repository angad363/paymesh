package com.paymesh.order.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

/**
 * Note what is absent: there is no merchantId field. The tenant comes from the access token, so a
 * caller cannot name the merchant they are writing under.
 * <p>
 * amountMinor is a boxed Long so that "absent" and "zero" are distinguishable -- a primitive would
 * default a missing amount to 0 and report it as a range error rather than a missing field.
 */
public record CreateOrderRequest(

    @Size(max = 40, message = "Customer id must not exceed 40 characters")
    String customerId,

    @Size(max = 100, message = "Merchant order reference must not exceed 100 characters")
    String merchantOrderReference,

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

    @Size(max = 500, message = "Description must not exceed 500 characters")
    String description,

    Map<String, String> metadata,

    Instant expiresAt
) {
}
