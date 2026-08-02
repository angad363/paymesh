package com.paymesh.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param role MERCHANT_ADMIN or MERCHANT_USER only. A merchant admin granting PLATFORM_ADMIN would
 *     be a tenant promoting somebody to platform staff, which is the one escalation the whole role
 *     model exists to prevent.
 */
public record GrantMerchantAccessRequest(

    @NotBlank(message = "Role is required")
    @Pattern(
        regexp = "^(MERCHANT_ADMIN|MERCHANT_USER)$",
        message = "Role must be MERCHANT_ADMIN or MERCHANT_USER"
    )
    String role
) {
}
