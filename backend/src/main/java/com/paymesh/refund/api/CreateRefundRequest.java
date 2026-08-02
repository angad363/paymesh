package com.paymesh.refund.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * @param amountMinor OPTIONAL. Omitted means "refund what is left" -- see
 *     {@code CreateRefundService}. A {@code Long} so absence is expressible; zero is a different
 *     request and {@code @Positive} refuses it.
 */
public record CreateRefundRequest(

    @Pattern(
        regexp = "^pi_[0-9a-fA-F-]{36}$",
        message = "Payment intent identifier must be a pi_ prefixed UUID"
    )
    String paymentIntentId,

    @Positive(message = "Refund amount must be a positive number of minor units")
    Long amountMinor,

    @Size(max = 100, message = "Merchant reference must be at most 100 characters")
    String merchantReference,

    @Size(max = 200, message = "Reason must be at most 200 characters")
    String reason
) {
    // NO CURRENCY FIELD, DELIBERATELY. A refund is denominated in whatever the payment collected,
    // so there is nothing for a caller to get wrong -- and no way to ask for 5000 JPY back from a
    // 5000 INR capture. tr_refunds_currency_matches enforces the same thing at the schema.
    //
    // NO merchantId FIELD either, like every other authenticated write here: the tenant comes from
    // the verified token.
}
