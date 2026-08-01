package com.paymesh.payment.domain;

import java.util.UUID;

/**
 * Opaque public identifier for a payment intent (ADR-003).
 * <p>
 * The prefix is {@code pi_}, not {@code pay_}, although ADR-003 lists both. {@code pay_} is
 * ambiguous between a payment intent and a future payment record, and an identifier cannot be
 * changed once a merchant has stored it. {@code pay_} stays reserved.
 */
public record PaymentIntentId(String value) {
    private static final String PREFIX = "pi_";

    public PaymentIntentId {
        validate(value);
    }

    public static PaymentIntentId generate() {
        return new PaymentIntentId(PREFIX + UUID.randomUUID());
    }

    public static PaymentIntentId from(String value) {
        return new PaymentIntentId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Payment Intent Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Payment Intent Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Payment Intent Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException(
                    "Payment Intent Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Payment Intent Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
