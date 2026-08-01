package com.paymesh.payment.domain;

import java.util.UUID;

/**
 * Opaque public identifier for a payment attempt (ADR-003).
 * <p>
 * The prefix is {@code pat_}, which is what SDD 12.4 already uses and which nothing else has spent.
 * No endpoint exposes an attempt yet, and the identifier is still opaque and prefixed from the
 * first row written: retrofitting an id format onto rows that already exist is not possible.
 */
public record PaymentAttemptId(String value) {
    private static final String PREFIX = "pat_";

    public PaymentAttemptId {
        validate(value);
    }

    public static PaymentAttemptId generate() {
        return new PaymentAttemptId(PREFIX + UUID.randomUUID());
    }

    public static PaymentAttemptId from(String value) {
        return new PaymentAttemptId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Payment Attempt Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Payment Attempt Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Payment Attempt Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException(
                    "Payment Attempt Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Payment Attempt Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
