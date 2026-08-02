package com.paymesh.customer.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * SDD 10.3's attach.
 *
 * <h2>THERE IS NO CARD NUMBER FIELD, AND THERE NEVER WILL BE</h2>
 *
 * A caller sends the PROVIDER'S TOKEN -- a handle the provider already holds the card behind. The
 * brand, last four and expiry are display details, enough to render "Visa ending 4242" and not
 * enough to charge anything. PayMesh claims no PCI compliance, and the only reliable way to
 * guarantee a PAN is never stored is for no field to be able to carry one.
 *
 * @param fingerprint the provider's stable identifier for the instrument, so the same card attached
 *     twice is recognisable as the same card
 */
public record AttachPaymentMethodTokenRequest(

    @NotBlank(message = "Provider is required")
    @Size(max = 50, message = "Provider must be at most 50 characters")
    String provider,

    @NotBlank(message = "Provider token is required")
    @Size(max = 255, message = "Provider token must be at most 255 characters")
    String providerToken,

    @NotBlank(message = "Fingerprint is required")
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "Fingerprint must be 64 hex characters")
    String fingerprint,

    @Size(max = 32, message = "Brand must be at most 32 characters")
    String brand,

    @Pattern(regexp = "^[0-9]{4}$", message = "Last four must be exactly four digits")
    String lastFour,

    @Min(value = 1, message = "Expiry month must be between 1 and 12")
    @Max(value = 12, message = "Expiry month must be between 1 and 12")
    Integer expiryMonth,

    @Min(value = 2000, message = "Expiry year must be a four-digit year")
    @Max(value = 2999, message = "Expiry year must be a four-digit year")
    Integer expiryYear
) {
}
