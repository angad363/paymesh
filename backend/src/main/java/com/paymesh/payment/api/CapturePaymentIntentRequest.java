package com.paymesh.payment.api;

import jakarta.validation.constraints.Positive;

/**
 * The body of a capture request. The body itself is optional, and so is the one field on it:
 * capturing without naming a figure means capturing the whole authorized amount, which is what a
 * merchant wants nearly every time.
 *
 * @param amountMinor how much to take, in MINOR UNITS, or null for all of it. Boxed rather than
 *                    primitive precisely so "absent" and "zero" stay different -- a primitive would
 *                    default a missing field to 0 and turn "take it all" into "take nothing", which
 *                    the aggregate would then refuse with a message about a positive amount.
 *                    <p>
 *                    Only positivity is checked here. The upper bound is the intent's own authorized
 *                    amount, which is a fact about the row and not about the request, so it cannot be
 *                    expressed as an annotation -- it belongs to the aggregate, and ultimately to
 *                    {@code ck_payment_intents_captured}.
 */
public record CapturePaymentIntentRequest(

    @Positive(message = "Amount must be a positive number of minor units")
    Long amountMinor
) {
}
