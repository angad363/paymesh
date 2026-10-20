package com.paymesh.identity.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Deliberately looser than RegisterUserRequest: only presence is checked. Applying
 * the registration format and length rules here would let a caller distinguish
 * "wrong shape" from "wrong credentials" and probe the password policy of accounts
 * created before it changed.
 */
public record LoginRequest(
    @NotBlank(message = "Email is required")
    String email,

    @NotBlank(message = "Password is required")
    String password
) {
}
