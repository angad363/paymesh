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

        String uuidPart = value.substring(PREFIX.length());

        try {
            // ROUND-TRIPPED, not merely parsed. UUID.fromString is lenient: it accepts uppercase
            // hex and padded shorthand like "1-1-1-1-1", both of which it happily turns INTO a
            // canonical UUID. Discarding the result therefore admitted two spellings of one
            // identifier -- and this is a primary key, so that is two rows for one thing. V26's
            // CHECK accepts only the canonical lowercase form; this is the Java half agreeing.
            if (!UUID.fromString(uuidPart).toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Payment Method Token Identifier contains a non-canonical UUID"
                );
            }
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
