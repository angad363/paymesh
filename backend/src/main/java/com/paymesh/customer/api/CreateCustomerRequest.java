package com.paymesh.customer.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Note what is absent: there is no merchantId field. The tenant comes from the access token, so a
 * caller cannot name the merchant they are writing under.
 * <p>
 * The other fields tolerate surrounding whitespace because the domain trims them, but {@code @Email}
 * does not: " a@b.test " is rejected here. That is stricter than the merchant API, which accepts a
 * padded email and lets the domain clean it. Worth aligning -- the two endpoints should not disagree
 * about what a valid email looks like.
 */
public record CreateCustomerRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 320, message = "Email must not exceed 320 characters")
    String email,

    @Size(max = 100, message = "Merchant reference must not exceed 100 characters")
    String merchantReference,

    @Size(max = 200, message = "Name must not exceed 200 characters")
    String name,

    @Size(max = 32, message = "Phone must not exceed 32 characters")
    String phone
) {
}
