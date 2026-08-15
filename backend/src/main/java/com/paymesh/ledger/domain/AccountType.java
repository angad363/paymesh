package com.paymesh.ledger.domain;

/**
 * The five accounts PayMesh posts to today, out of SDD 15.1's nine.
 *
 * <h2>EACH CARRIES ITS OWN NORMAL BALANCE, AND THAT IS THE POINT OF THE ENUM</h2>
 *
 * The normal balance is a fact about the KIND of account, not about an individual row, so it
 * belongs to the type rather than being passed in at construction where a caller could get it
 * wrong. A merchant pending account created with a {@code DEBIT} normal balance would report every
 * merchant as owing PayMesh the exact amount PayMesh owes them, with every individual entry
 * correct and no constraint violated. Here that row cannot be written.
 *
 * <h2>Why only five</h2>
 *
 * The others each need a producer that does not exist: {@code MERCHANT_RESERVED} needs holds,
 * {@code PLATFORM_FEE_REVENUE} needs a fee schedule this codebase does not have, and
 * {@code REFUND_RECEIVABLE} needs a refund that is owed by the provider rather than taken out of
 * the merchant. Adding a constant here without the posting that credits it produces an account that
 * exists, reads zero forever, and implies a capability that is missing.
 * <p>
 * {@code BANK_CASH} and {@code SETTLEMENT_IN_TRANSIT} were on that list until V32. Settlement is
 * their producer and it now exists.
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
    MERCHANT_PENDING(Direction.CREDIT),

    /**
     * What PayMesh owes a merchant and has cleared for payout. Also a LIABILITY, and also
     * CREDIT-normal: it is the same money as {@link #MERCHANT_PENDING}, owed to the same person.
     *
     * <h2>THE DIFFERENCE IS PERMISSION, NOT ACCOUNTING</h2>
     *
     * A release moves value between two liabilities of one merchant, so it nets to zero against
     * PayMesh's own position -- which is exactly why it is a balanced transaction rather than an
     * adjustment. Nothing is created; a claim simply stops being conditional.
     *
     * <p>This constant was named in this class's own javadoc as deliberately absent, because it
     * "needs a settlement schedule to move money out of pending". V29 and
     * {@code ReleaseAvailableFundsService} are that schedule, which is what makes adding it now
     * different from adding it then: there is a producer.
     */
    MERCHANT_AVAILABLE(Direction.CREDIT),

    /**
     * A merchant's money committed to a settlement batch. A LIABILITY, CREDIT-normal, and still the
     * merchant's -- what changed is that it can no longer be settled a second time.
     *
     * <h2>SDD 17.6 INVARIANT 2 IS THE REASON THIS ACCOUNT EXISTS AT ALL</h2>
     *
     * Funds must never go straight from available to the bank. Between the two there is a window in
     * which PayMesh has committed to a payout and does not yet know whether it landed, and money in
     * that window is neither settleable nor gone. Without this account that window has no
     * representation, so a payout in flight either double-settles (still available) or vanishes
     * from the merchant's balance (already paid) -- and one of those is a duplicate payment.
     */
    SETTLEMENT_IN_TRANSIT(Direction.CREDIT),

    /**
     * PayMesh's own bank position. An ASSET, DEBIT-normal, platform-owned, one per currency.
     * <p>
     * A completed payout CREDITS this, because paying a merchant reduces PayMesh's cash. That is
     * the one place in this ledger where money leaves the platform rather than moving inside it,
     * and it is why the entry is only ever posted on a provider's confirmation.
     */
    BANK_CASH(Direction.DEBIT);

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
        return this == MERCHANT_PENDING
            || this == MERCHANT_AVAILABLE
            || this == SETTLEMENT_IN_TRANSIT;
    }
}
