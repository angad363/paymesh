package com.paymesh.identity.domain;

import java.util.UUID;

/**
 * Opaque public identifier for a user: "usr_" + UUID (ADR-003).
 * Mirrors MerchantId deliberately rather than sharing a base type -- the two
 * capabilities are separate modules that will become separate services.
 */
public record UserId(String value) {
    private static final String PREFIX = "usr_";

    public UserId {
        validate(value);
    }

    public static UserId generate() {
        return new UserId(PREFIX + UUID.randomUUID());
    }

    public static UserId from(String value) {
        return new UserId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("User Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("User Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("User Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("User Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("User Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
