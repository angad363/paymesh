package com.paymesh.refund.domain;

import java.util.UUID;

/**
 * A refund's opaque public identifier, {@code ref_} + UUID (ADR-003).
 * <p>
 * Unlike the Ledger's {@code lac_}/{@code ltx_}, this prefix is not new: ADR-003 reserved
 * {@code ref_} for this capability before it was written, and this is it being used.
 */
public record RefundId(String value) {

    private static final String PREFIX = "ref_";

    public RefundId {
        validate(value);
    }

    public static RefundId generate() {
        return new RefundId(PREFIX + UUID.randomUUID());
    }

    public static RefundId from(String value) {
        return new RefundId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Refund Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Refund Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Refund Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Refund Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Refund Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
