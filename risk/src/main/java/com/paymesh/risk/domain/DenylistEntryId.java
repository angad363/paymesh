package com.paymesh.risk.domain;

import java.util.UUID;

/** A denylist entry's opaque public identifier, {@code dnl_} + UUID (ADR-003). */
public record DenylistEntryId(String value) {

    private static final String PREFIX = "dnl_";

    public DenylistEntryId {
        validate(value);
    }

    public static DenylistEntryId generate() {
        return new DenylistEntryId(PREFIX + UUID.randomUUID());
    }

    public static DenylistEntryId from(String value) {
        return new DenylistEntryId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Denylist Entry Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Denylist Entry Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Denylist Entry Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            // Round-tripped, not merely parsed: UUID.fromString accepts uppercase hex and padded
            // shorthand, canonicalising both. ADR-029 and V26 -- one UUID, one spelling.
            if (!UUID.fromString(uuidPart).toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Denylist Entry Identifier contains a non-canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Denylist Entry Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
