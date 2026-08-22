package com.paymesh.audit.domain;

import java.util.UUID;

/**
 * An audit export's opaque public identifier, {@code aex_} + UUID (ADR-003, ADR-035).
 *
 * <p>The same shape as {@code ReportExportId}; distinct prefix so an id names its kind on sight.
 */
public record AuditExportId(String value) {

    private static final String PREFIX = "aex_";

    public AuditExportId {
        validate(value);
    }

    public static AuditExportId generate() {
        return new AuditExportId(PREFIX + UUID.randomUUID());
    }

    public static AuditExportId from(String value) {
        return new AuditExportId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Audit Export Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Audit Export Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Audit Export Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Audit Export Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Audit Export Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
