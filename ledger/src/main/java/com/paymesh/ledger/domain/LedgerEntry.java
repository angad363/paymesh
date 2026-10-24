package com.paymesh.ledger.domain;

/**
 * One debit or credit line. A value, not an entity: it has no identity of its own in the domain,
 * only inside {@link LedgerTransaction}, which is the unit anything ever names or reads.
 * <p>
 * The database gives it a {@code BIGSERIAL} so it has a primary key, but nothing addresses it.
 */
public record LedgerEntry(
    LedgerAccountId ledgerAccountId,
    Direction direction,
    long amountMinor
) {

    public LedgerEntry {
        if (ledgerAccountId == null) {
            throw new IllegalArgumentException("A ledger entry must name an account");
        }

        if (direction == null) {
            throw new IllegalArgumentException("A ledger entry must have a direction");
        }

        // Positive, never zero. Zero is not a movement of money, it is a row that makes a journal
        // look busier than it is -- and a journal of two zero entries would satisfy "debits equal
        // credits" perfectly.
        if (amountMinor <= 0) {
            throw new IllegalArgumentException(
                "A ledger entry amount must be a positive number of minor units, got " + amountMinor
            );
        }
    }

    public static LedgerEntry debit(LedgerAccountId ledgerAccountId, long amountMinor) {
        return new LedgerEntry(ledgerAccountId, Direction.DEBIT, amountMinor);
    }

    public static LedgerEntry credit(LedgerAccountId ledgerAccountId, long amountMinor) {
        return new LedgerEntry(ledgerAccountId, Direction.CREDIT, amountMinor);
    }
}
