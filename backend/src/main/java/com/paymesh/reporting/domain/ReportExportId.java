package com.paymesh.reporting.domain;

import java.util.UUID;

/**
 * An export's opaque public identifier, {@code rex_} + UUID (ADR-003, ADR-034).
 *
 * <p>The only identifier this capability mints. A {@code report_facts} row is keyed by the
 * {@code evt_} that produced it and has no id of its own, because nothing addresses one fact -- a
 * merchant asks for a summary or an export, never for a single row.
 */
public record ReportExportId(String value) {

    private static final String PREFIX = "rex_";

    public ReportExportId {
        validate(value);
    }

    public static ReportExportId generate() {
        return new ReportExportId(PREFIX + UUID.randomUUID());
    }

    public static ReportExportId from(String value) {
        return new ReportExportId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Report Export Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Report Export Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Report Export Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Report Export Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Report Export Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
