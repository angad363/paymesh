package com.paymesh.customer.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * A card on file. SDD 10.4.
 *
 * <h2>THE TABLE HAS EXISTED SINCE V3 AND THIS IS ITS FIRST WRITER</h2>
 *
 * SDD 10.3's attach and detach endpoints were never built, so no card was ever on file for any
 * customer -- "attach a payment method" on a payment intent attached the string "CARD". V6 even
 * fixed a tenant foreign key on a table nobody could insert into. ADR-023.
 *
 * <h2>IT STORES A REFERENCE, NEVER A CARD</h2>
 *
 * {@code providerToken} is the provider's opaque handle. The brand, last four and expiry are
 * DISPLAY DETAILS -- enough to render "Visa ending 4242" and not enough to charge anything. There
 * is no field on this type that could hold a PAN, which is the only reliable way to guarantee one
 * is never stored.
 *
 * @param fingerprint the provider's stable identifier for the underlying instrument, so the same
 *     card attached twice is recognisable as the same card. It is what
 *     {@code uq_payment_method_tokens_live_fingerprint} keys on.
 * @param detachedAt null while live. Detach is a timestamp, not a delete: a deleted token cannot
 *     answer "was this card on file when that payment was taken".
 */
public record PaymentMethodToken(
    PaymentMethodTokenId paymentMethodTokenId,
    MerchantId merchantId,
    CustomerId customerId,
    String provider,
    String providerToken,
    String fingerprint,
    String brand,
    String lastFour,
    Integer expiryMonth,
    Integer expiryYear,
    Instant detachedAt,
    Instant createdAt
) {

    public PaymentMethodToken {
        if (paymentMethodTokenId == null || merchantId == null || customerId == null) {
            throw new IllegalArgumentException("A payment method token must identify its owner");
        }

        provider = requireText(provider, "Provider");
        providerToken = requireText(providerToken, "Provider token");

        if (fingerprint == null || !fingerprint.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("A fingerprint must be 64 hex characters");
        }

        // A four-digit string, not a number: "0042" is a real card ending and 42 is not.
        if (lastFour != null && !lastFour.matches("^[0-9]{4}$")) {
            throw new IllegalArgumentException("Last four must be exactly four digits");
        }

        if (expiryMonth != null && (expiryMonth < 1 || expiryMonth > 12)) {
            throw new IllegalArgumentException("Expiry month must be between 1 and 12");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("A payment method token must have a creation instant");
        }
    }

    public static PaymentMethodToken attach(
        MerchantId merchantId,
        CustomerId customerId,
        String provider,
        String providerToken,
        String fingerprint,
        String brand,
        String lastFour,
        Integer expiryMonth,
        Integer expiryYear,
        Instant createdAt
    ) {
        return new PaymentMethodToken(
            PaymentMethodTokenId.generate(), merchantId, customerId, provider, providerToken,
            fingerprint, brand, lastFour, expiryMonth, expiryYear, null, createdAt
        );
    }

    public PaymentMethodToken detach(Instant detachedAt) {
        if (this.detachedAt != null) {
            throw new PaymentMethodTokenAlreadyDetachedException(paymentMethodTokenId);
        }

        return new PaymentMethodToken(
            paymentMethodTokenId, merchantId, customerId, provider, providerToken, fingerprint,
            brand, lastFour, expiryMonth, expiryYear, detachedAt, createdAt
        );
    }

    public boolean isLive() {
        return detachedAt == null;
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }

        return value.strip();
    }
}
