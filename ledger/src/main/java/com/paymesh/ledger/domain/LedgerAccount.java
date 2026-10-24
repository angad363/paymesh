package com.paymesh.ledger.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One line of the chart of accounts (SDD 15.1).
 *
 * <h2>THE REFERENCE IS THE ADDRESS, AND IT IS BUILT HERE ONLY</h2>
 *
 * SDD 15.4's posting contract addresses accounts by string -- {@code provider-clearing:INR},
 * {@code merchant:mrc_<uuid>:pending:INR} -- and the database makes that string the unique key,
 * because the alternative key contains a NULL for platform accounts and PostgreSQL does not collide
 * NULLs (see V15). That makes the spelling load-bearing: two spellings of one account are two
 * accounts, and money posted through both leaves each of them wrong.
 * <p>
 * So the two factory methods below are the only places a reference is ever constructed. Nothing
 * takes a reference as an argument, and nothing concatenates one at a call site.
 */
public record LedgerAccount(
    LedgerAccountId ledgerAccountId,
    String accountReference,
    MerchantId merchantId,
    AccountType accountType,
    String currency,
    Instant createdAt
) {

    public LedgerAccount {
        if (ledgerAccountId == null) {
            throw new IllegalArgumentException("Ledger account identifier is required");
        }

        if (accountReference == null || accountReference.isBlank()) {
            throw new IllegalArgumentException("Ledger account reference is required");
        }

        if (accountType == null) {
            throw new IllegalArgumentException("Ledger account type is required");
        }

        currency = requireCurrency(currency);

        // The Java-side mirror of ck_ledger_accounts_owner. A merchant account without a merchant
        // would be posted to correctly and then never appear in any balance -- the money would be
        // in the ledger and invisible, which is worse than an error.
        if (accountType.isMerchantOwned() == (merchantId == null)) {
            throw new IllegalArgumentException(
                accountType.isMerchantOwned()
                    ? "A " + accountType + " account must belong to a merchant"
                    : "A " + accountType + " account must not belong to a merchant"
            );
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("Ledger account creation instant is required");
        }
    }

    /**
     * The platform's clearing account for one currency: {@code provider-clearing:INR}.
     * <p>
     * One per currency for the whole installation, not one per provider. A per-provider account
     * would be the more precise model and is what a reconciliation against a provider statement
     * eventually wants -- but PayMesh has exactly one provider concept and a single shared callback
     * secret to go with it (README lists that as a known gap), so splitting the account now would
     * invent a distinction the rest of the platform cannot yet make.
     */
    public static LedgerAccount providerClearing(String currency, Instant createdAt) {
        String normalised = requireCurrency(currency);

        return new LedgerAccount(
            LedgerAccountId.generate(),
            "provider-clearing:" + normalised,
            null,
            AccountType.PROVIDER_CLEARING,
            normalised,
            createdAt
        );
    }

    /**
     * A merchant's pending balance for one currency:
     * {@code merchant:mrc_<uuid>:pending:INR}.
     */
    public static LedgerAccount merchantPending(
        MerchantId merchantId,
        String currency,
        Instant createdAt
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("A merchant pending account requires a merchant");
        }

        String normalised = requireCurrency(currency);

        return new LedgerAccount(
            LedgerAccountId.generate(),
            "merchant:" + merchantId.value() + ":pending:" + normalised,
            merchantId,
            AccountType.MERCHANT_PENDING,
            normalised,
            createdAt
        );
    }

    /**
     * The other half of a merchant's position: what has cleared the holding period.
     * <p>
     * Reference is {@code merchant:<id>:available:<CCY>}, deliberately parallel to the pending
     * account's so the pair reads as one merchant's two buckets rather than as two unrelated
     * accounts. Same currency scoping, same uniqueness, same normal balance -- the ONLY difference
     * between these two accounts is whether the money in them can be paid out.
     */
    public static LedgerAccount merchantAvailable(
        MerchantId merchantId,
        String currency,
        Instant createdAt
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("A merchant available account requires a merchant");
        }

        String normalised = requireCurrency(currency);

        return new LedgerAccount(
            LedgerAccountId.generate(),
            "merchant:" + merchantId.value() + ":available:" + normalised,
            merchantId,
            AccountType.MERCHANT_AVAILABLE,
            normalised,
            createdAt
        );
    }

    /**
     * A merchant's money committed to a settlement batch:
     * {@code merchant:mrc_<uuid>:in-transit:INR}.
     * <p>
     * The third bucket in the same family as pending and available, spelled the same way for the
     * same reason. A merchant's total claim on PayMesh is the sum of all three.
     */
    public static LedgerAccount settlementInTransit(
        MerchantId merchantId,
        String currency,
        Instant createdAt
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("A settlement in-transit account requires a merchant");
        }

        String normalised = requireCurrency(currency);

        return new LedgerAccount(
            LedgerAccountId.generate(),
            "merchant:" + merchantId.value() + ":in-transit:" + normalised,
            merchantId,
            AccountType.SETTLEMENT_IN_TRANSIT,
            normalised,
            createdAt
        );
    }

    /**
     * PayMesh's own cash for one currency: {@code bank-cash:INR}.
     * <p>
     * Platform-owned, like {@link #providerClearing}, and one per currency for the whole
     * installation rather than one per bank account. PayMesh has one bank concept, and splitting
     * the account per destination would invent a distinction nothing else here can make.
     */
    public static LedgerAccount bankCash(String currency, Instant createdAt) {
        String normalised = requireCurrency(currency);

        return new LedgerAccount(
            LedgerAccountId.generate(),
            "bank-cash:" + normalised,
            null,
            AccountType.BANK_CASH,
            normalised,
            createdAt
        );
    }

    /** The direction that increases this account. Delegates to the type; see {@link AccountType}. */
    public Direction normalBalance() {
        return accountType.normalBalance();
    }

    /**
     * Uppercased, because the reference is a unique key and {@code inr} and {@code INR} must not be
     * able to become two accounts holding half a balance each.
     */
    private static String requireCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Currency is required");
        }

        String normalised = currency.strip().toUpperCase();

        if (!normalised.matches("^[A-Z]{3}$")) {
            throw new IllegalArgumentException("Currency must be three letters, got " + currency);
        }

        return normalised;
    }
}
