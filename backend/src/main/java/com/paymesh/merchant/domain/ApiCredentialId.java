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

        try {
            UUID.fromString(value.substring(PREFIX.length()));
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
