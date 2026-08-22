package com.paymesh.audit.domain;

import java.util.UUID;

/**
 * An audit event's opaque public identifier, {@code aud_} + UUID (ADR-003, ADR-035).
 *
 * <p>Minted by the {@code AuditRecorder} adapter when it writes the row. The same validate-in-the-
 * compact-constructor shape every {@code XxxId} in this codebase has, so a malformed id cannot be
 * constructed and {@code ck_audit_events_id_format} is the database saying the same thing.
 */
public record AuditEventId(String value) {

    private static final String PREFIX = "aud_";

    public AuditEventId {
        validate(value);
    }

    public static AuditEventId generate() {
        return new AuditEventId(PREFIX + UUID.randomUUID());
    }

    public static AuditEventId from(String value) {
        return new AuditEventId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Audit Event Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Audit Event Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Audit Event Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Audit Event Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Audit Event Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
