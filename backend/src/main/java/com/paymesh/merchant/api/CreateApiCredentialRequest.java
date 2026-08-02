package com.paymesh.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param role MERCHANT_ADMIN or MERCHANT_USER. Required rather than defaulted: defaulting would
 *     mean the safest choice is the one a caller has to know to ask for, and every key would end up
 *     admin.
 * @param label required, because "which of these six keys is the CI one" is the question an
 *     operator asks at exactly the moment they need to revoke one quickly
 */
public record CreateApiCredentialRequest(

    @NotBlank(message = "Role is required")
    @Pattern(
        regexp = "^\\s*(?i:MERCHANT_ADMIN|MERCHANT_USER)\\s*$",
        message = "Role must be MERCHANT_ADMIN or MERCHANT_USER"
    )
    String role,

    @NotBlank(message = "Label is required")
    @Size(max = 100, message = "Label must be at most 100 characters")
    String label
) {
}
