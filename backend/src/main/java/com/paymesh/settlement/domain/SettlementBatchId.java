package com.paymesh.settlement.domain;

import java.util.UUID;

/**
 * A settlement batch's opaque public identifier, {@code stl_} + UUID (ADR-003).
 * <p>
 * {@code stl_} was reserved for this capability in ADR-003 and named as planned in CLAUDE.md
 * before Settlement existed. This is it being used.
 */
public record SettlementBatchId(String value) {

    private static final String PREFIX = "stl_";

    public SettlementBatchId {
        validate(value);
    }

    public static SettlementBatchId generate() {
        return new SettlementBatchId(PREFIX + UUID.randomUUID());
    }

    public static SettlementBatchId from(String value) {
        return new SettlementBatchId(value);
    }

    private static void validate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Settlement batch identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Settlement batch identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Settlement batch identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Settlement batch identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
