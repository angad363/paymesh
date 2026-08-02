package com.paymesh.merchant.domain;

import java.util.UUID;

/** {@code kyc_} + UUID (ADR-003). */
public record KycSubmissionId(String value) {

    private static final String PREFIX = "kyc_";

    public KycSubmissionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("KYC Submission Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("KYC Submission Identifier must start with " + PREFIX);
        }

        try {
            UUID.fromString(value.substring(PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "KYC Submission Identifier contains an invalid UUID", exception
            );
        }
    }

    public static KycSubmissionId generate() {
        return new KycSubmissionId(PREFIX + UUID.randomUUID());
    }

    public static KycSubmissionId from(String value) {
        return new KycSubmissionId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
