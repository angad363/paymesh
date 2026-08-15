package com.paymesh.ledger.application;

import com.paymesh.ledger.domain.LedgerAccount;
import com.paymesh.ledger.domain.LedgerEntry;
import com.paymesh.ledger.domain.LedgerTransaction;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The posting service against hand-written repositories -- no Spring, no database, a fixed
 * {@link Clock}.
 * <p>
 * This is what manual bean wiring buys (java-coding-conventions.md 13): the service is a plain
 * {@code final} class with three constructor arguments, so the whole thing is exercised in
 * milliseconds. The database-enforced half of the same rules is proved in
 * {@code LedgerIntegrationTest}, which is slow and needs Docker.
 */
class PostPaymentCapturedServiceTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final String PAYMENT_INTENT_ID = "pi_00000000-0000-0000-0000-000000000001";
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-02T11:00:00Z");
    private static final Instant POSTED_AT = Instant.parse("2026-08-02T11:00:05Z");

    private final FakeAccounts accounts = new FakeAccounts();
    private final FakeTransactions transactions = new FakeTransactions();

    private final PostPaymentCapturedService service = new PostPaymentCapturedService(
        accounts, transactions, Clock.fixed(POSTED_AT, ZoneOffset.UTC)
    );

    @Test
    void postsABalancedJournalForACapturedPayment() {
        LedgerTransaction posted = post(99900).orElseThrow();

        assertThat(posted.totalDebitsMinor()).isEqualTo(99900);
        assertThat(posted.totalCreditsMinor()).isEqualTo(99900);
        assertThat(posted.transactionType()).isEqualTo(LedgerTransaction.PAYMENT_CAPTURED);
        assertThat(posted.referenceId()).isEqualTo(PAYMENT_INTENT_ID);
        assertThat(posted.occurredAt())
            .as("the authority's clock from the event, not the posting instant")
            .isEqualTo(OCCURRED_AT);
        assertThat(posted.createdAt()).isEqualTo(POSTED_AT);
    }

    /** Accounts are opened on first use rather than seeded by a migration. */
    @Test
    void opensTheProviderClearingAndMerchantPendingAccountsOnFirstUse() {
        post(99900);

        assertThat(accounts.byReference.keySet()).containsExactlyInAnyOrder(
            "provider-clearing:INR",
            "merchant:" + MERCHANT.value() + ":pending:INR"
        );
    }

    /** A second payment reuses them; two accounts at one address would each hold half a balance. */
    @Test
    void reusesTheAccountsForASecondPaymentInTheSameCurrency() {
        post(99900);
        post("pi_00000000-0000-0000-0000-000000000002", 50000);

        assertThat(accounts.byReference).hasSize(2);
        assertThat(accounts.opens)
            .as("opened once each; the second posting found them")
            .isEqualTo(2);
    }

    @Test
    void opensASeparateAccountPairPerCurrency() {
        post(99900);
        post("pi_00000000-0000-0000-0000-000000000002", 50000, "USD");

        assertThat(accounts.byReference).hasSize(4);
    }

    // --- idempotency -----------------------------------------------------------------------------

    /**
     * THE SECOND GUARD, WHICH IS NOT THE INBOX. {@code processed_events} stops one event being
     * applied twice; this stops one PAYMENT being posted twice however many events describe it.
     * <p>
     * <b>Sabotage that must turn this red:</b> key
     * {@code LedgerTransaction.paymentCapturedIdempotencyKey} on anything per-call -- the event id,
     * a UUID -- and the second posting writes a second journal, doubling the merchant's balance.
     */
    @Test
    void postsOnceWhenTheSameCaptureArrivesTwice() {
        LedgerTransaction first = post(99900).orElseThrow();
        LedgerTransaction second = post(99900).orElseThrow();

        assertThat(transactions.posted).hasSize(1);
        assertThat(second.ledgerTransactionId()).isEqualTo(first.ledgerTransactionId());
    }

    /**
     * The pre-read is the fast path; the unique index is the guard. When a concurrent posting slips
     * past the read, the exception is deliberately NOT caught -- it rolls back the dispatcher's
     * transaction along with the inbox row, and the redelivered event finds the winner's journal.
     */
    @Test
    void letsAConcurrentDuplicatePropagateSoTheEventIsRedelivered() {
        transactions.failNextPostAsDuplicate = true;

        assertThatThrownBy(() -> post(99900))
            .isInstanceOf(LedgerTransactionAlreadyPostedException.class);
    }

    // --- nothing to post -------------------------------------------------------------------------

    /**
     * A SUCCEEDED payment that captured nothing. Reachable, and a journal of two zero entries would
     * balance perfectly while recording no movement of money -- so {@link LedgerEntry} refuses the
     * zero amount and this returns before building one.
     * <p>
     * Without the guard the handler throws, the event never drains, and it retries forever.
     */
    @Test
    void postsNothingWhenTheCaptureWasZero() {
        assertThat(post(0)).isEmpty();
        assertThat(transactions.posted).isEmpty();
        assertThat(accounts.byReference)
            .as("not even the accounts are opened for a posting that will not happen")
            .isEmpty();
    }

    @Test
    void postsNothingWhenTheCaptureWasNegative() {
        assertThat(post(-1)).isEmpty();
        assertThat(transactions.posted).isEmpty();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private Optional<LedgerTransaction> post(long amountMinor) {
        return post(PAYMENT_INTENT_ID, amountMinor, "INR");
    }

    private Optional<LedgerTransaction> post(String paymentIntentId, long amountMinor) {
        return post(paymentIntentId, amountMinor, "INR");
    }

    private Optional<LedgerTransaction> post(String paymentIntentId, long amountMinor, String currency) {
        return service.post(MERCHANT, paymentIntentId, amountMinor, currency, OCCURRED_AT);
    }

    /** Models {@code ON CONFLICT DO NOTHING} followed by a read: opening a taken address is a no-op. */
    private static final class FakeAccounts implements LedgerAccountRepository {

        private final Map<String, LedgerAccount> byReference = new LinkedHashMap<>();
        private int opens;

        @Override
        public Optional<LedgerAccount> findByReference(String accountReference) {
            return Optional.ofNullable(byReference.get(accountReference));
        }

        @Override
        public LedgerAccount open(LedgerAccount candidate) {
            LedgerAccount existing = byReference.get(candidate.accountReference());

            if (existing != null) {
                return existing;
            }

            opens++;
            byReference.put(candidate.accountReference(), candidate);

            return candidate;
        }
    }

    private static final class FakeTransactions implements LedgerTransactionRepository {

        @Override
        public java.util.List<ReleasableCapture> findUnreleasedCaptures(int limit) {
            return java.util.List.of();
        }


        private final Map<String, LedgerTransaction> posted = new HashMap<>();
        private boolean failNextPostAsDuplicate;

        @Override
        public LedgerTransaction post(LedgerTransaction transaction) {
            if (failNextPostAsDuplicate) {
                throw new LedgerTransactionAlreadyPostedException(transaction.idempotencyKey());
            }

            if (posted.containsKey(transaction.idempotencyKey())) {
                throw new LedgerTransactionAlreadyPostedException(transaction.idempotencyKey());
            }

            posted.put(transaction.idempotencyKey(), transaction);

            return transaction;
        }

        @Override
        public Optional<LedgerTransaction> findByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(posted.get(idempotencyKey));
        }

        @Override
        public int lockPaymentJournals(String paymentIntentId) {
            return posted.size();
        }
    }
}
