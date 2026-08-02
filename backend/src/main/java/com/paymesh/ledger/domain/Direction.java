package com.paymesh.ledger.domain;

/**
 * Which side of the journal an entry sits on.
 *
 * <h2>WHY DIRECTION IS AN ENUM AND NOT THE SIGN OF THE AMOUNT</h2>
 *
 * SDD 15.6's third invariant: "amounts are positive integers in minor units; direction is stored
 * separately". A signed amount makes {@code DEBIT 500} and {@code CREDIT -500} two spellings of one
 * fact, and then every sum in the system has to know which spelling it is reading. Worse, it makes
 * {@code amount > 0} unenforceable, so a typo that flips a sign becomes a valid row rather than a
 * rejected one.
 * <p>
 * Kept separate, the database can say {@code CHECK (amount_minor > 0)} and mean it.
 */
public enum Direction {

    DEBIT,
    CREDIT;

    /**
     * The signed contribution of {@code amountMinor} to an account whose {@code normalBalance} is
     * this direction's opposite or same.
     * <p>
     * An entry increases an account when it lands on that account's normal side and decreases it
     * otherwise. That one sentence is all of double-entry's arithmetic, and it lives here rather
     * than in a SQL {@code CASE} so that the domain test and the balance query cannot disagree
     * about it.
     */
    public long signedAgainst(Direction normalBalance, long amountMinor) {
        return this == normalBalance ? amountMinor : -amountMinor;
    }
}
