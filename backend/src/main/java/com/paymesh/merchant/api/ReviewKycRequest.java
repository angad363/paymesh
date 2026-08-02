package com.paymesh.merchant.api;

import jakarta.validation.constraints.Size;

/** @param notes the operator's reasoning. Optional on approve, and worth writing on reject. */
public record ReviewKycRequest(
    @Size(max = 500, message = "Review notes must be at most 500 characters")
    String notes
) {
}
