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

        String uuidPart = value.substring(PREFIX.length());

        try {
            // ROUND-TRIPPED, not merely parsed. UUID.fromString is lenient: it accepts uppercase
            // hex and padded shorthand like "1-1-1-1-1", both of which it happily turns INTO a
            // canonical UUID. Discarding the result therefore admitted two spellings of one
            // identifier -- and this is a primary key, so that is two rows for one thing. V26's
            // CHECK accepts only the canonical lowercase form; this is the Java half agreeing.
            if (!UUID.fromString(uuidPart).toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "KYC Submission Identifier contains a non-canonical UUID"
                );
            }
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
