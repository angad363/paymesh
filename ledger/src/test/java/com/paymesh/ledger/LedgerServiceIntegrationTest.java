package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.paymesh.ledger.LedgerTestEvents.AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE LEDGER'S INVARIANTS, THROUGH THE SERVICE (ADR-050). Same assertions as the monolith's
 * {@code LedgerIntegrationTest}, against this deployable's own schema, Flyway V1 and event
 * consumers. The raw-SQL tests go round the application on purpose: if they pass with the domain
 * checks deleted, the trigger is real.
 * <p>
 * Deliberately NOT {@code @Transactional}: {@code tr_ledger_entries_balanced} is DEFERRED and only
 * fires at COMMIT, which a rolled-back test transaction would never reach.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class LedgerServiceIntegrationTest {

    @Autowired
    private EventDispatcher dispatcher;

    @Autowired
    private GetBalancesService balances;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void postsAJournalAndMovesABalanceWhenPaymentSucceededIsDelivered() {
        MerchantId merchantId = MerchantId.generate();

        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(merchantId, LedgerTestEvents.newPaymentIntentId(), 4000));

        assertThat(balances.forMerchant(merchantId))
            .containsExactly(new MerchantBalance("INR", 4000L, 0L));
    }

    /** The same business fact, delivered twice (inbox) and re-emitted under a NEW event id (idempotency key). */
    @Test
    void postsOnceWhenTheSameBusinessFactIsDeliveredTwice() {
        MerchantId merchantId = MerchantId.generate();
        String paymentIntentId = LedgerTestEvents.newPaymentIntentId();
        OutboxEvent event = LedgerTestEvents.paymentSucceeded(merchantId, paymentIntentId, 4000);

        dispatcher.dispatch(event);
        dispatcher.dispatch(event);
        assertThat(journalCount(merchantId)).as("same event id: the inbox absorbs it").isEqualTo(1);

        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(EventId.generate(), merchantId, paymentIntentId, 4000));
        assertThat(journalCount(merchantId))
            .as("a different event id for the same payment: uq_ledger_transactions_idempotency absorbs it")
            .isEqualTo(1);
        assertThat(balances.forMerchant(merchantId))
            .containsExactly(new MerchantBalance("INR", 4000L, 0L));
    }

    @Test
    void refusesAnUnbalancedJournalAtCommitEvenFromRawSql() {
        MerchantId merchantId = MerchantId.generate();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "unbalanced-" + UUID.randomUUID());
            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            insertEntry(transactionId, openAccount(merchantId, "MERCHANT_PENDING"), "CREDIT", 3000);
            return null;
        })).hasStackTraceContaining("unbalanced");
    }

    @Test
    void refusesAJournalWithASingleEntryAtCommit() {
        MerchantId merchantId = MerchantId.generate();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "one-sided-" + UUID.randomUUID());
            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            return null;
        })).hasStackTraceContaining("a journal needs at least two");
    }

    @Test
    void refusesAJournalThatMovesADifferentMerchantsMoney() {
        MerchantId header = MerchantId.generate();
        MerchantId owner = MerchantId.generate();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(header, "cross-tenant-" + UUID.randomUUID());
            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            insertEntry(transactionId, openAccount(owner, "MERCHANT_PENDING"), "CREDIT", 4000);
            return null;
        })).hasStackTraceContaining("moves");
    }

    @Test
    void refusesToUpdateOrDeleteAPostedEntryAndToUpdateItsHeader() {
        MerchantId merchantId = MerchantId.generate();
        dispatcher.dispatch(LedgerTestEvents.paymentSucceeded(merchantId, LedgerTestEvents.newPaymentIntentId(), 4000));
        String transactionId = jdbc
            .sql("select ledger_transaction_id from ledger_transactions where merchant_id = ?")
            .param(merchantId.value()).query(String.class).single();

        assertThatThrownBy(() -> jdbc
            .sql("update ledger_entries set amount_minor = 1 where ledger_transaction_id = ?")
            .param(transactionId).update()).hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc
            .sql("delete from ledger_entries where ledger_transaction_id = ?")
            .param(transactionId).update()).hasStackTraceContaining("immutable");
        assertThatThrownBy(() -> jdbc
            .sql("update ledger_transactions set currency = 'USD' where ledger_transaction_id = ?")
            .param(transactionId).update()).hasStackTraceContaining("immutable");
    }

    @Test
    void refusesASecondJournalWithTheSameIdempotencyKeyEvenFromRawSql() {
        MerchantId merchantId = MerchantId.generate();
        String key = "dup-" + UUID.randomUUID();
        insertJournalHeader(merchantId, key);

        assertThatThrownBy(() -> insertJournalHeader(merchantId, key))
            .hasStackTraceContaining("uq_ledger_transactions_idempotency");
    }

    private long journalCount(MerchantId merchantId) {
        return jdbc.sql("select count(*) from ledger_transactions where merchant_id = ?")
            .param(merchantId.value()).query(Long.class).single();
    }

    private String insertJournalHeader(MerchantId merchantId, String idempotencyKey) {
        String transactionId = "ltx_" + UUID.randomUUID();

        jdbc.sql("""
                insert into ledger_transactions (ledger_transaction_id, merchant_id, transaction_type,
                                                 reference_type, reference_id, currency,
                                                 idempotency_key, occurred_at, created_at)
                values (?, ?, 'PAYMENT_CAPTURED', 'PAYMENT_INTENT', ?, 'INR', ?, ?, ?)
                """)
            .params(transactionId, merchantId.value(), "pi_" + UUID.randomUUID(), idempotencyKey,
                AT.atOffset(ZoneOffset.UTC), AT.atOffset(ZoneOffset.UTC))
            .update();

        return transactionId;
    }

    private void insertEntry(String transactionId, String accountId, String direction, long amountMinor) {
        jdbc.sql("""
                insert into ledger_entries (ledger_transaction_id, ledger_account_id, direction,
                                            amount_minor, currency, created_at)
                values (?, ?, ?, ?, 'INR', ?)
                """)
            .params(transactionId, accountId, direction, amountMinor, AT.atOffset(ZoneOffset.UTC))
            .update();
    }

    /** Insert-if-absent, so repeated calls are safe. */
    private String openAccount(MerchantId merchantId, String accountType) {
        String reference = merchantId == null
            ? "provider-clearing:INR"
            : "merchant:" + merchantId.value() + ":pending:INR";

        jdbc.sql("""
                insert into ledger_accounts (ledger_account_id, account_reference, merchant_id,
                                             account_type, currency, normal_balance, created_at)
                values (?, ?, ?, ?, 'INR', ?, ?)
                on conflict (account_reference) do nothing
                """)
            .params("lac_" + UUID.randomUUID(), reference,
                merchantId == null ? null : merchantId.value(), accountType,
                merchantId == null ? "DEBIT" : "CREDIT", AT.atOffset(ZoneOffset.UTC))
            .update();

        return jdbc.sql("select ledger_account_id from ledger_accounts where account_reference = ?")
            .param(reference).query(String.class).single();
    }
}
