package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

/**
 * The input to "confirm a payment intent".
 * <p>
 * merchantId comes from the verified access token, never from the request body. There is no status
 * field and no amount: confirm requests an action against an intent whose money was fixed at
 * creation, so there is nothing here for a caller to move.
 * <p>
 * A record rather than four loose parameters because two of them are adjacent Strings, and a caller
 * that transposes {@code returnUrl} and {@code device} would compile.
 *
 * @param returnUrl where the customer returns after an off-site step, or null. Stored on the
 *                  attempt after redaction; nothing reads it yet.
 * @param device    an opaque client hint, or null. Same.
 */
public record ConfirmPaymentIntentCommand(
    MerchantId merchantId,
    PaymentIntentId paymentIntentId,
    String returnUrl,
    String device
) {
}
