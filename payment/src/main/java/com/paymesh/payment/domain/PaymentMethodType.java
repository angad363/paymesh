package com.paymesh.payment.domain;

import java.util.Locale;

/**
 * The KIND of instrument a payment will be collected with -- not the instrument itself.
 * <p>
 * SDD 12.2 describes attach as taking a tokenized method, meaning a {@code payment_method_tokens}
 * row. That table exists (V3), has no JPA entity, and <b>nothing can create a row in it</b>: the
 * Provider Simulator, which is what mints tokens, does not exist. Requiring a token would make the
 * attach endpoint uncallable, and issuing one here would be building the simulator inside Payment.
 * A type is the smallest thing that is both truthful and useful today.
 * <p>
 * When tokens become real, {@code payment_method_token_id} joins {@code payment_intents} as a
 * nullable column beside {@code payment_method_type}; this enum does not change.
 * <p>
 * <b>Raw instrument data never appears here or anywhere else in Payment</b> (SDD 12.6). There is no
 * card number, no expiry and no holder name in this vocabulary, deliberately.
 */
public enum PaymentMethodType {
    CARD,
    UPI,
    NET_BANKING,
    WALLET;

    /**
     * Parses a caller-supplied method type.
     * <p>
     * {@code valueOf} would do, but its message names the Java enum class, and an error body must
     * not hand a caller internal type names.
     */
    public static PaymentMethodType parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Payment method type cannot be null");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown payment method type: " + value);
        }
    }
}
