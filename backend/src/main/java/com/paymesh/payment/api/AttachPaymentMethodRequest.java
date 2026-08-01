package com.paymesh.payment.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of an attach request: the KIND of instrument, and nothing else.
 * <p>
 * There is no token, no card number, no expiry and no holder name -- not "not yet", but not here at
 * all. SDD 12.2 describes attaching a tokenized method, and the table that would hold one
 * ({@code payment_method_tokens}, V3) has nothing that can write to it until the Provider Simulator
 * exists. A type is the smallest thing that is truthful today, and Payment never stores raw
 * instrument data in any case (SDD 12.6).
 * <p>
 * Parsed rather than bound as an enum so an unknown value produces the domain's message instead of
 * Jackson naming the Java type.
 */
public record AttachPaymentMethodRequest(

    @NotBlank(message = "Payment method type is required")
    @Pattern(
        regexp = "^\\s*(?i:CARD|UPI|NET_BANKING|WALLET)\\s*$",
        message = "Payment method type must be CARD, UPI, NET_BANKING or WALLET"
    )
    String paymentMethodType
) {
}
