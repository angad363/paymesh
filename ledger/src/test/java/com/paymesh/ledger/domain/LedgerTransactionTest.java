package com.paymesh.ledger.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The balance invariant, in the one place a caller can still be told about it in a sentence.
 * <p>
 * Plain JUnit, no Spring: {@link LedgerTransaction} is a record with a constructor, and every rule
 * below is enforced there. The database enforces the same rules again with the application out of
 * the path -- {@code LedgerIntegrationTest} proves that half.
 */
class LedgerTransactionTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final LedgerAccountId CLEARING = LedgerAccountId.generate();
    private static final LedgerAccountId PENDING = LedgerAccountId.generate();
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T11:00:00Z");
    private static final Instant CREATED_AT = Instant.parse("2026-08-02T11:00:05Z");

    @Test
    void postsAPaymentCaptureAsOneDebitAndOneCreditOfTheSameAmount() {
        LedgerTransaction transaction = paymentCaptured(99900);

        assertThat(transaction.entries()).hasSize(2);
        assertThat(transaction.totalDebitsMinor()).isEqualTo(99900);
        assertThat(transaction.totalCreditsMinor()).isEqualTo(99900);
    }

    /**
     * THE DIRECTIONS, AND THEY ARE NOT INTERCHANGEABLE.
     * <p>
     * The provider owes PayMesh the captured amount, so PayMesh's receivable -- an asset -- is
     * DEBITED. PayMesh in turn owes the merchant, so that liability is CREDITED.
     * <p>
     * <b>Sabotage that must turn this red:</b> swap the two entries in
     * {@code LedgerTransaction.paymentCaptured}. The journal still balances perfectly, every
     * constraint in V15 still passes, and every merchant's balance comes out negative.
     */
    @Test
    void debitsProviderClearingAndCreditsTheMerchant() {
        LedgerTransaction transaction = paymentCaptured(99900);

        LedgerEntry clearing = entryFor(transaction, CLEARING);
        LedgerEntry pending = entryFor(transaction, PENDING);

        assertThat(clearing.direction()).isEqualTo(Direction.DEBIT);
        assertThat(pending.direction()).isEqualTo(Direction.CREDIT);
    }

    /**
     * The two-entry posting with no fee split, which is the deliberate divergence from SDD 15.2.
     * The merchant is credited the GROSS.
     */
    @Test
    void creditsTheMerchantTheGrossWithNoFeeDeducted() {
        assertThat(entryFor(paymentCaptured(99900), PENDING).amountMinor()).isEqualTo(99900);
    }

    /** SDD 15.4's key shape. Pinned, because the uniqueness guarantee is only as good as the key. */
    @Test
    void keysIdempotencyOnThePaymentRatherThanTheEvent() {
        assertThat(paymentCaptured(99900).idempotencyKey())
            .isEqualTo("payment-captured:pi_00000000-0000-0000-0000-000000000001");
    }

    // --- the invariant ---------------------------------------------------------------------------

    /**
     * THE ONE RULE THE MODULE EXISTS FOR. Constructed directly rather than through
     * {@code paymentCaptured}, because that factory cannot produce an unbalanced journal -- which is
     * the point of it.
     */
    @Test
    void refusesAJournalWhoseDebitsDoNotEqualItsCredits() {
        assertThatThrownBy(() -> transactionWith(List.of(
            LedgerEntry.debit(CLEARING, 99900),
            LedgerEntry.credit(PENDING, 97000)
        )))
            .isInstanceOf(UnbalancedTransactionException.class)
            .hasMessageContaining("99900")
            .hasMessageContaining("97000");
    }

    /**
     * An empty list sums to 0 == 0 and would sail through a naive balance check. The same trap is
     * why {@code ledger_assert_balanced} counts entries before comparing sums.
     */
    @Test
    void refusesAJournalWithNoEntriesEvenThoughNothingIsUnbalanced() {
        assertThatThrownBy(() -> transactionWith(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two entries");
    }

    @Test
    void refusesAJournalWithASingleEntry() {
        assertThatThrownBy(() -> transactionWith(List.of(LedgerEntry.debit(CLEARING, 99900))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least two entries");
    }

    /**
     * Two zero entries balance perfectly and record no movement of money. Refused at the entry
     * rather than at the journal, so no assembled journal can contain one.
     */
    @Test
    void refusesAZeroAmountEntry() {
        assertThatThrownBy(() -> LedgerEntry.debit(CLEARING, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void refusesANegativeAmountEntry() {
        assertThatThrownBy(() -> LedgerEntry.credit(PENDING, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    /** A balanced journal of more than two lines is legal -- a fee split will be exactly that. */
    @Test
    void acceptsABalancedJournalOfMoreThanTwoEntries() {
        LedgerTransaction transaction = transactionWith(List.of(
            LedgerEntry.debit(CLEARING, 99900),
            LedgerEntry.credit(PENDING, 97000),
            LedgerEntry.credit(LedgerAccountId.generate(), 2900)
        ));

        assertThat(transaction.totalDebitsMinor()).isEqualTo(transaction.totalCreditsMinor());
    }

    // --- normalization ---------------------------------------------------------------------------

    @Test
    void uppercasesTheCurrency() {
        assertThat(transactionWith(balanced(), "inr").currency()).isEqualTo("INR");
    }

    @Test
    void refusesACurrencyThatIsNotThreeLetters() {
        assertThatThrownBy(() -> transactionWith(balanced(), "RUPEES"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("three letters");
    }

    @Test
    void refusesAJournalWithNoMerchant() {
        assertThatThrownBy(() -> new LedgerTransaction(
            LedgerTransactionId.generate(), null, LedgerTransaction.PAYMENT_CAPTURED,
            LedgerTransaction.REFERENCE_PAYMENT_INTENT, "pi_x", "INR", "k", balanced(),
            OCCURRED_AT, CREATED_AT
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("merchant");
    }

    /**
     * The entries list is defensively copied, so a caller holding the original cannot add an
     * unbalancing line after the constructor has approved it.
     */
    @Test
    void cannotBeUnbalancedAfterConstructionByMutatingTheListItWasGiven() {
        List<LedgerEntry> mutable = new ArrayList<>(balanced());
        LedgerTransaction transaction = transactionWith(mutable);

        mutable.add(LedgerEntry.debit(CLEARING, 5000));

        assertThat(transaction.entries()).hasSize(2);
        assertThat(transaction.totalDebitsMinor()).isEqualTo(transaction.totalCreditsMinor());
    }

    // --- helpers ---------------------------------------------------------------------------------

    private static LedgerTransaction paymentCaptured(long amountMinor) {
        return LedgerTransaction.paymentCaptured(
            MERCHANT,
            "pi_00000000-0000-0000-0000-000000000001",
            CLEARING,
            PENDING,
            amountMinor,
            "INR",
            OCCURRED_AT,
            CREATED_AT
        );
    }

    private static List<LedgerEntry> balanced() {
        return List.of(LedgerEntry.debit(CLEARING, 99900), LedgerEntry.credit(PENDING, 99900));
    }

    private static LedgerTransaction transactionWith(List<LedgerEntry> entries) {
        return transactionWith(entries, "INR");
    }

    private static LedgerTransaction transactionWith(List<LedgerEntry> entries, String currency) {
        return new LedgerTransaction(
            LedgerTransactionId.generate(),
            MERCHANT,
            LedgerTransaction.PAYMENT_CAPTURED,
            LedgerTransaction.REFERENCE_PAYMENT_INTENT,
            "pi_x",
            currency,
            "payment-captured:pi_x",
            entries,
            OCCURRED_AT,
            CREATED_AT
        );
    }

    private static LedgerEntry entryFor(LedgerTransaction transaction, LedgerAccountId accountId) {
        return transaction.entries().stream()
            .filter(entry -> entry.ledgerAccountId().equals(accountId))
            .findFirst()
            .orElseThrow();
    }
}
