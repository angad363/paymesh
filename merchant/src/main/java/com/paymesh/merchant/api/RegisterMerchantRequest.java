package com.paymesh.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterMerchantRequest(
    @NotBlank(message = "Business name is required.")
    @Size(
        max = 200,
        message = "Business name must not exceed 200 characters"
    )
    String businessName,

    @NotBlank(message = "Email is required")
    String email,

    @NotBlank(message = "Country is required")
    String country,

    @NotBlank(message = "Default currency is required")
    String defaultCurrency
) {
}
