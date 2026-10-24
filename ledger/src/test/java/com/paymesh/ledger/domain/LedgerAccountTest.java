package com.paymesh.ledger.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The chart of accounts, and mostly the reference strings.
 * <p>
 * They look like formatting and they are not: {@code uq_ledger_accounts_reference} makes the
 * spelling the identity of the account, so two spellings of one account are two accounts, each
 * holding part of a balance, with no constraint violated and nothing reading as an error.
 */
class LedgerAccountTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_00000000-0000-0000-0000-000000000001");
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T11:00:00Z");

    @Test
    void addressesTheProviderClearingAccountByCurrency() {
        assertThat(LedgerAccount.providerClearing("INR", CREATED_AT).accountReference())
            .isEqualTo("provider-clearing:INR");
    }

    @Test
    void addressesAMerchantPendingAccountByMerchantAndCurrency() {
        assertThat(LedgerAccount.merchantPending(MERCHANT, "INR", CREATED_AT).accountReference())
            .isEqualTo("merchant:mrc_00000000-0000-0000-0000-000000000001:pending:INR");
    }

    /**
     * {@code inr} and {@code INR} must not become two accounts. The uppercase happens before the
     * reference is built, so the reference itself is normalized rather than just the column.
     */
    @Test
    void normalizesTheCurrencyBeforeBuildingTheReference() {
        assertThat(LedgerAccount.merchantPending(MERCHANT, " inr ", CREATED_AT).accountReference())
            .isEqualTo("merchant:mrc_00000000-0000-0000-0000-000000000001:pending:INR");
    }

    // --- normal balance --------------------------------------------------------------------------

    /**
     * PROVIDER CLEARING IS AN ASSET, MERCHANT PENDING IS A LIABILITY, and getting this backwards is
     * the quiet catastrophe: the entries stay correct, the journal stays balanced, and every
     * merchant's reported balance flips sign.
     */
    @Test
    void growsProviderClearingOnTheDebitSideAndMerchantPendingOnTheCredit() {
        assertThat(LedgerAccount.providerClearing("INR", CREATED_AT).normalBalance())
            .isEqualTo(Direction.DEBIT);

        assertThat(LedgerAccount.merchantPending(MERCHANT, "INR", CREATED_AT).normalBalance())
            .isEqualTo(Direction.CREDIT);
    }

    /**
     * The arithmetic the balance query reimplements in SQL. Stated once here so the two cannot drift
     * apart without a test noticing.
     */
    @Test
    void countsAnEntryOnItsAccountsNormalSideAsAnIncrease() {
        assertThat(Direction.CREDIT.signedAgainst(Direction.CREDIT, 99900)).isEqualTo(99900);
        assertThat(Direction.DEBIT.signedAgainst(Direction.CREDIT, 99900)).isEqualTo(-99900);
        assertThat(Direction.DEBIT.signedAgainst(Direction.DEBIT, 99900)).isEqualTo(99900);
        assertThat(Direction.CREDIT.signedAgainst(Direction.DEBIT, 99900)).isEqualTo(-99900);
    }

    // --- ownership -------------------------------------------------------------------------------

    /**
     * The Java mirror of {@code ck_ledger_accounts_owner}. A merchant account with no merchant would
     * be posted to correctly and then never appear in any balance -- the money would be in the
     * ledger and invisible.
     */
    @Test
    void refusesAMerchantAccountWithNoMerchant() {
        assertThatThrownBy(() -> new LedgerAccount(
            LedgerAccountId.generate(), "merchant:x:pending:INR", null,
            AccountType.MERCHANT_PENDING, "INR", CREATED_AT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must belong to a merchant");
    }

    @Test
    void refusesAPlatformAccountThatNamesAMerchant() {
        assertThatThrownBy(() -> new LedgerAccount(
            LedgerAccountId.generate(), "provider-clearing:INR", MERCHANT,
            AccountType.PROVIDER_CLEARING, "INR", CREATED_AT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not belong to a merchant");
    }

    @Test
    void refusesACurrencyThatIsNotThreeLetters() {
        assertThatThrownBy(() -> LedgerAccount.providerClearing("RUPEES", CREATED_AT))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("three letters");
    }
}
