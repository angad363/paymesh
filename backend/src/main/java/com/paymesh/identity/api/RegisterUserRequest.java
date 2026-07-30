package com.paymesh.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param merchantId optional. When present, the new user is granted MERCHANT_ADMIN
 *                   scoped to that merchant.
 */
public record RegisterUserRequest(
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    @Size(max = 320, message = "Email must not exceed 320 characters")
    String email,

    // 12 is the floor, 72 the ceiling: BCrypt hashes only the first 72 bytes and
    // silently ignores the rest, which would make two long passwords equivalent.
    @NotBlank(message = "Password is required")
    @Size(min = 12, max = 72, message = "Password must be between 12 and 72 characters")
    String password,

    @Size(max = 40, message = "Merchant identifier must not exceed 40 characters")
    String merchantId
) {
}
