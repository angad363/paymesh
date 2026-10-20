package com.paymesh.customer.api;

import com.paymesh.customer.domain.PaymentMethodToken;

import java.time.Instant;

/**
 * A card on file, WITHOUT the provider token.
 * <p>
 * There is deliberately no field for it. The token is the one thing in the row that could charge
 * the card, the caller already supplied it, and a response that echoes it turns every list into a
 * way to harvest chargeable handles. Display details only.
 */
public record PaymentMethodTokenResponse(
    String id,
    String customerId,
    String provider,
    String brand,
    String lastFour,
    Integer expiryMonth,
    Integer expiryYear,
    Instant detachedAt,
    Instant createdAt
) {

    public static PaymentMethodTokenResponse from(PaymentMethodToken token) {
        return new PaymentMethodTokenResponse(
            token.paymentMethodTokenId().value(),
            token.customerId().value(),
            token.provider(),
            token.brand(),
            token.lastFour(),
            token.expiryMonth(),
            token.expiryYear(),
            token.detachedAt(),
            token.createdAt()
        );
    }
}
