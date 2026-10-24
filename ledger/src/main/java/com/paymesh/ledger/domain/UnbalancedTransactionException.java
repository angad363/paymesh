package com.paymesh.ledger.domain;

/**
 * A journal whose debits do not equal its credits, refused before it can be written.
 * <p>
 * An {@code IllegalArgumentException} would have been consistent with the rest of this codebase's
 * domain invariants, which is what {@code java-coding-conventions.md} prescribes. This one is named
 * because it is the invariant the module exists to protect, and a named type is what lets a test
 * assert that a lopsided journal was refused for THAT reason rather than for a null argument three
 * lines earlier. It extends {@code IllegalArgumentException} so nothing that catches the general
 * case stops working.
 */
public final class UnbalancedTransactionException extends IllegalArgumentException {

    private final long totalDebitsMinor;
    private final long totalCreditsMinor;

    public UnbalancedTransactionException(long totalDebitsMinor, long totalCreditsMinor) {
        super(
            "A ledger transaction must balance: debits " + totalDebitsMinor
                + " do not equal credits " + totalCreditsMinor
        );

        this.totalDebitsMinor = totalDebitsMinor;
        this.totalCreditsMinor = totalCreditsMinor;
    }

    public long totalDebitsMinor() {
        return totalDebitsMinor;
    }

    public long totalCreditsMinor() {
        return totalCreditsMinor;
    }
}
