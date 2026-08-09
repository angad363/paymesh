package com.paymesh.risk.domain;

import java.util.UUID;

/** One risk evaluation's opaque public identifier, {@code rsk_} + UUID (ADR-003). */
public record RiskAssessmentId(String value) {

    private static final String PREFIX = "rsk_";

    public RiskAssessmentId {
        validate(value);
    }

    public static RiskAssessmentId generate() {
        return new RiskAssessmentId(PREFIX + UUID.randomUUID());
    }

    public static RiskAssessmentId from(String value) {
        return new RiskAssessmentId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Risk Assessment Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Risk Assessment Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Risk Assessment Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            // Round-tripped, not merely parsed: UUID.fromString accepts uppercase hex and padded
            // shorthand, canonicalising both. ADR-029 and V26 -- one UUID, one spelling.
            if (!UUID.fromString(uuidPart).toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Risk Assessment Identifier contains a non-canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Risk Assessment Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
