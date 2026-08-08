package com.paymesh.merchant.domain;

import java.util.UUID;

/** An API credential's opaque public identifier, {@code apc_} + UUID (ADR-003). */
public record ApiCredentialId(String value) {

    private static final String PREFIX = "apc_";

    public ApiCredentialId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("API Credential Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("API Credential Identifier must start with " + PREFIX);
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
                    "API Credential Identifier contains a non-canonical UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "API Credential Identifier contains an invalid UUID", exception
            );
        }
    }

    public static ApiCredentialId generate() {
        return new ApiCredentialId(PREFIX + UUID.randomUUID());
    }

    public static ApiCredentialId from(String value) {
        return new ApiCredentialId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
