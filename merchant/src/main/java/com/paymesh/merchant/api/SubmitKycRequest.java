package com.paymesh.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * NO DOCUMENTS. PayMesh claims no compliance and processes no real money; a field accepting a
 * passport scan would be there to make a checklist green rather than to verify anybody, and it
 * would be the single worst thing in this repository.
 */
public record SubmitKycRequest(

    @NotBlank(message = "Legal name is required")
    @Size(max = 200, message = "Legal name must be at most 200 characters")
    String legalName,

    @NotBlank(message = "Registration identifier is required")
    @Size(max = 100, message = "Registration identifier must be at most 100 characters")
    String registrationId
) {
}
