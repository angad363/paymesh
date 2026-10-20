package com.paymesh.settlement.domain;

import java.util.UUID;

/**
 * A payout's opaque public identifier, {@code po_} + UUID (ADR-003).
 * <p>
 * The value PayMesh sends the provider as its own reference, which is what makes a resubmitted
 * payout idempotent on the provider's side ({@code uq_provider_payouts_external_reference}).
 */
public record PayoutId(String value) {

    private static final String PREFIX = "po_";

    public PayoutId {
        validate(value);
    }

    public static PayoutId generate() {
        return new PayoutId(PREFIX + UUID.randomUUID());
    }

    public static PayoutId from(String value) {
        return new PayoutId(value);
    }

    private static void validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payout identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Payout identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Payout identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Payout identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
