package com.paymesh.customer.api;

import jakarta.validation.constraints.Size;

/** @param reason the merchant's own note. Optional -- PayMesh has no opinion on why. */
public record BlockCustomerRequest(
    @Size(max = 200, message = "Reason must be at most 200 characters")
    String reason
) {
}
