package com.paymesh.ledger.domain;

/**
 * The two accounts a captured payment touches, out of SDD 15.1's nine.
 *
 * <h2>EACH CARRIES ITS OWN NORMAL BALANCE, AND THAT IS THE POINT OF THE ENUM</h2>
 *
 * The normal balance is a fact about the KIND of account, not about an individual row, so it
 * belongs to the type rather than being passed in at construction where a caller could get it
 * wrong. A merchant pending account created with a {@code DEBIT} normal balance would report every
 * merchant as owing PayMesh the exact amount PayMesh owes them, with every individual entry
 * correct and no constraint violated. Here that row cannot be written.
 *
 * <h2>Why only two</h2>
 *
 * The others each need a producer that does not exist: {@code MERCHANT_AVAILABLE} needs a
 * settlement schedule to move money out of pending, {@code MERCHANT_RESERVED} needs holds,
 * {@code BANK_CASH} and {@code SETTLEMENT_IN_TRANSIT} need Settlement (Phase 2),
 * {@code PLATFORM_FEE_REVENUE} needs a fee schedule this codebase does not have, and
 * {@code REFUND_RECEIVABLE} needs Refund. Adding a constant here without the posting that credits
 * it produces an account that exists, reads zero forever, and implies a capability that is missing.
 */
public enum AccountType {

    /**
     * PayMesh's receivable from the payment provider. An ASSET: money the provider has collected
     * and owes PayMesh, so it grows on the debit side.
     * <p>
     * Platform-owned -- there is one per currency for the whole installation, not one per merchant.
     * {@code ck_ledger_accounts_owner} refuses a row of this type that names a merchant.
     */
    PROVIDER_CLEARING(Direction.DEBIT),

    /**
     * What PayMesh owes a merchant and has not yet paid out. A LIABILITY, so it grows on the credit
     * side: crediting it means PayMesh owes the merchant more.
     * <p>
     * "Pending" rather than "available" because nothing here makes money settleable. Without a
     * settlement schedule every balance stays pending forever, and calling it available would claim
     * a merchant could withdraw it.
     */
    MERCHANT_PENDING(Direction.CREDIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The direction that INCREASES an account of this type. */
    public Direction normalBalance() {
        return normalBalance;
    }

    /** True when accounts of this type belong to a merchant rather than the platform. */
    public boolean isMerchantOwned() {
        return this == MERCHANT_PENDING;
    }
}
