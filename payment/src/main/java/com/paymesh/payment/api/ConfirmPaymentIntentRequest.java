package com.paymesh.payment.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of a confirm request. Both fields are optional, and so is the body itself -- confirming
 * a payment that needs no off-site step needs nothing said about it.
 * <p>
 * Note what is absent: no amount, no currency, no payment method. Those were fixed at creation and
 * attach, and a confirm that could restate them would be a second chance to change the obligation
 * after the merchant already agreed it.
 * <p>
 * NOTHING READS EITHER FIELD YET. They are validated for shape and stored on the attempt's
 * request_payload after redaction, so that the record of what was asked for exists from the first
 * attempt rather than from whenever a provider starts using them.
 */
public record ConfirmPaymentIntentRequest(

    /**
     * Where the customer comes back to after an off-site step. Constrained to absolute http(s) so a
     * malformed value is refused at the boundary rather than silently dropped by the redactor, and
     * so a {@code javascript:} URL cannot be stored and later handed to a browser.
     */
    @Size(max = 500, message = "Return URL must not exceed 500 characters")
    @Pattern(
        regexp = "^\\s*https?://[^\\s]+\\s*$",
        message = "Return URL must be an absolute http or https URL"
    )
    String returnUrl,

    @Size(max = 120, message = "Device must not exceed 120 characters")
    String device
) {
}
