package com.paymesh.ledger.domain;

import java.util.UUID;

/** A journal header's opaque public identifier, {@code ltx_} + UUID. See {@link LedgerAccountId}. */
public record LedgerTransactionId(String value) {

    private static final String PREFIX = "ltx_";

    public LedgerTransactionId {
        validate(value);
    }

    public static LedgerTransactionId generate() {
        return new LedgerTransactionId(PREFIX + UUID.randomUUID());
    }

    public static LedgerTransactionId from(String value) {
        return new LedgerTransactionId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Ledger Transaction Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Ledger Transaction Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Ledger Transaction Identifier must start with " + PREFIX
            );
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException(
                    "Ledger Transaction Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Ledger Transaction Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
