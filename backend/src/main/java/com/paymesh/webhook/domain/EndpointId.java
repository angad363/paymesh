package com.paymesh.webhook.domain;

import java.util.UUID;

/**
 * A webhook endpoint's opaque public identifier, {@code whe_} + UUID (ADR-003).
 * <p>
 * Reserved for this capability before it was written, like {@code ref_} was for Refund. It is also
 * an input to the signing secret's derivation (ADR-028 §2), which is why it must be exactly what it
 * claims to be: a derivation keyed on a malformed id would still produce 32 plausible bytes.
 */
public record EndpointId(String value) {

    private static final String PREFIX = "whe_";

    public EndpointId {
        validate(value);
    }

    public static EndpointId generate() {
        return new EndpointId(PREFIX + UUID.randomUUID());
    }

    public static EndpointId from(String value) {
        return new EndpointId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Webhook Endpoint Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Webhook Endpoint Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Webhook Endpoint Identifier must start with " + PREFIX
            );
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Webhook Endpoint Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Webhook Endpoint Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
