package com.paymesh.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Note what is absent: there is no merchantId field, and no status field. The tenant comes from the
 * access token, so a caller cannot name the merchant they are writing under; the status is decided
 * by the state machine, so a caller cannot name a state either.
 * <p>
 * There is no {@code clientSecret} in the response this produces, and SDD 12.3's is deliberately
 * not issued: a credential nothing can verify looks like an authorization boundary and is not one.
 * <p>
 * amountMinor is a boxed Long so that "absent" and "zero" are distinguishable -- a primitive would
 * default a missing amount to 0 and report it as a range error rather than a missing field.
 */
public record CreatePaymentIntentRequest(

    @NotBlank(message = "Order id is required")
    @Size(max = 40, message = "Order id must not exceed 40 characters")
    String orderId,

    @Size(max = 40, message = "Customer id must not exceed 40 characters")
    String customerId,

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

    /**
     * Optional; AUTOMATIC when absent, which is what most merchants want. Parsed rather than bound
     * as an enum so an unknown value produces the domain's message instead of Jackson naming the
     * Java type.
     */
    @Pattern(
        regexp = "^\\s*(?i:AUTOMATIC|MANUAL)\\s*$",
        message = "Capture method must be AUTOMATIC or MANUAL"
    )
    String captureMethod,

    @Size(max = 500, message = "Description must not exceed 500 characters")
    String description,

    Map<String, String> metadata
) {
}
