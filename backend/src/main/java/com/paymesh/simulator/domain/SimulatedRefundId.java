package com.paymesh.simulator.domain;

import java.util.UUID;

/**
 * Opaque public identifier for a simulated provider refund (ADR-003).
 * <p>
 * {@code sim_ref_}, not {@code ref_}: that prefix is reserved for PayMesh's own Refund capability,
 * which lands later, and one of the two would have had to move. A provider's refund id and a
 * PayMesh refund id are different objects that will one day sit in adjacent columns.
 */
public record SimulatedRefundId(String value) {

    private static final String PREFIX = "sim_ref_";

    public SimulatedRefundId {
        validate(value);
    }

    public static SimulatedRefundId generate() {
        return new SimulatedRefundId(PREFIX + UUID.randomUUID());
    }

    public static SimulatedRefundId from(String value) {
        return new SimulatedRefundId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Simulated Refund Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Simulated Refund Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Simulated Refund Identifier must start with " + PREFIX
            );
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException(
                    "Simulated Refund Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Simulated Refund Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
