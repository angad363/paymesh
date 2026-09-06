package com.paymesh.simulator.domain;

import java.util.UUID;

/**
 * The provider's own identifier for a payout, {@code sim_po_} + UUID.
 * <p>
 * Beside {@code sim_pay_} and {@code sim_ref_}, and deliberately not PayMesh's {@code po_}: the
 * provider names its own records. PayMesh's id travels separately as {@code externalReference},
 * which is what the provider deduplicates a resubmission on.
 */
public record SimulatedPayoutId(String value) {

    private static final String PREFIX = "sim_po_";

    public SimulatedPayoutId {
        if (value == null || !value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("A simulated payout identifier starts with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            if (!UUID.fromString(uuidPart).toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Simulated payout identifier has an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Simulated payout identifier has an invalid UUID", exception
            );
        }
    }

    public static SimulatedPayoutId generate() {
        return new SimulatedPayoutId(PREFIX + UUID.randomUUID());
    }

    public static SimulatedPayoutId from(String value) {
        return new SimulatedPayoutId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
