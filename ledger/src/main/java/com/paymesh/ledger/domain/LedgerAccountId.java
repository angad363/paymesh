package com.paymesh.ledger.domain;

import java.util.UUID;

/**
 * A ledger account's opaque public identifier, {@code lac_} + UUID (ADR-003).
 * <p>
 * The prefix is new. ADR-003's planned list names {@code cus_}, {@code ord_}, {@code pi_},
 * {@code pay_}, {@code ref_}, {@code stl_}, {@code whe_} and {@code evt_} and stops there, because
 * the ledger was not designed when it was written. {@code lac_} and {@link LedgerTransactionId}'s
 * {@code ltx_} are chosen here and recorded in ADR-018 -- three letters rather than two so they
 * cannot be confused with a future {@code la_}/{@code lt_}, and neither is a prefix of the other.
 */
public record LedgerAccountId(String value) {

    private static final String PREFIX = "lac_";

    public LedgerAccountId {
        validate(value);
    }

    public static LedgerAccountId generate() {
        return new LedgerAccountId(PREFIX + UUID.randomUUID());
    }

    public static LedgerAccountId from(String value) {
        return new LedgerAccountId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Ledger Account Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Ledger Account Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Ledger Account Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Ledger Account Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Ledger Account Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
