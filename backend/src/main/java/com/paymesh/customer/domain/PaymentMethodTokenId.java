package com.paymesh.customer.domain;

import java.util.UUID;

/** {@code pmt_} + UUID (ADR-003). */
public record PaymentMethodTokenId(String value) {

    private static final String PREFIX = "pmt_";

    public PaymentMethodTokenId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payment Method Token Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Payment Method Token Identifier must start with " + PREFIX
            );
        }

        try {
            UUID.fromString(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Payment Method Token Identifier contains an invalid UUID", exception
            );
        }
    }

    public static PaymentMethodTokenId generate() {
        return new PaymentMethodTokenId(PREFIX + UUID.randomUUID());
    }

    public static PaymentMethodTokenId from(String value) {
        return new PaymentMethodTokenId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
