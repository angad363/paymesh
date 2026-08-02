package com.paymesh.refund.application;

import com.paymesh.shared.tenant.MerchantId;

/**
 * @param merchantId derived from the verified token by the controller, never read from a body
 * @param amountMinor null means "refund what is left" -- see {@code CreateRefundService}. A
 *     {@code Long} rather than a {@code long} precisely so absence is expressible; zero would be a
 *     different request and is refused.
 * @param actorId the user who asked, for the timeline. Null when no principal can be named.
 */
public record CreateRefundCommand(
    MerchantId merchantId,
    String paymentIntentId,
    Long amountMinor,
    String merchantReference,
    String reason,
    String actorId
) {
}
