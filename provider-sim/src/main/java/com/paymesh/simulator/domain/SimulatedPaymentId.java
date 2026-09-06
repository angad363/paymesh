package com.paymesh.simulator.domain;

import java.util.UUID;

/**
 * Opaque public identifier for a simulated provider payment (ADR-003).
 * <p>
 * The prefix is {@code sim_pay_}, which collides with none of PayMesh's own ({@code mrc_},
 * {@code usr_}, {@code cus_}, {@code ord_}, {@code pi_}, {@code pay_}, {@code evt_}) and with none
 * of the reserved ones. That matters more here than elsewhere: <b>this value crosses the boundary
 * and is stored inside PayMesh as {@code payment_attempts.provider_reference}</b>, so an id that
 * looked like a PayMesh id would be actively misleading in a support conversation about a table
 * that holds both.
 * <p>
 * It is also the string the existing payment tests and Postman collection already use for provider
 * references, which is deliberate continuity rather than coincidence.
 */
public record SimulatedPaymentId(String value) {

    private static final String PREFIX = "sim_pay_";

    public SimulatedPaymentId {
        validate(value);
    }

    public static SimulatedPaymentId generate() {
        return new SimulatedPaymentId(PREFIX + UUID.randomUUID());
    }

    public static SimulatedPaymentId from(String value) {
        return new SimulatedPaymentId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Simulated Payment Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Simulated Payment Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Simulated Payment Identifier must start with " + PREFIX
            );
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Simulated Payment Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Simulated Payment Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
