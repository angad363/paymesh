package com.paymesh.ledger;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.ledger.application.GetBalancesService;
import com.paymesh.ledger.application.MerchantBalance;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CapturePaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE LEDGER AGAINST A REAL POSTGRESQL, and the half of the design that Java cannot prove.
 * <p>
 * {@code LedgerTransactionTest} shows the domain refusing an unbalanced journal in a sentence. That
 * check can be refactored around, bypassed by a direct INSERT, or skipped by a migration. Everything
 * below goes round the application on purpose -- raw JDBC into the tables -- and asserts that the
 * database refuses anyway. If these pass while the domain checks are deleted, the invariant is real;
 * if they pass only because the domain checks exist, they are proving nothing.
 * <p>
 * Deliberately NOT {@code @Transactional}. Two reasons, and the second is the important one:
 * <ul>
 *   <li>An outer test transaction would make every assertion pass regardless of whether the
 *       dispatcher opened one of its own.</li>
 *   <li><b>{@code tr_ledger_entries_balanced} is DEFERRED, so it only fires at COMMIT.</b> A test
 *       that rolls back would never reach it, and the most important constraint in the module would
 *       be exercised by nothing.</li>
 * </ul>
 * Each test therefore registers its own merchant and scopes its queries to it.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class LedgerIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final long ORDER_AMOUNT_MINOR = 4000;
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private GetBalancesService balances;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private CapturePaymentIntentService capturePaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbacks;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcClient jdbc;

    // --- THE LOOP -------------------------------------------------------------------------------

    /**
     * THE HEADLINE: a payment succeeds and a balance exists, which was not true of this codebase
     * before this branch. README said so -- "no balance moves anywhere in this codebase."
     * <p>
     * Nothing here calls the Ledger. Payment writes {@code payment.succeeded}, the relay reads it,
     * and the Ledger posts because it subscribed -- which is why
     * {@code ModuleBoundaryTest.ledgerNeverImportsPayment} has an empty allowlist.
     */
    @Test
    void postsAJournalAndMovesABalanceWhenAPaymentSucceeds() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        assertThat(pendingBalance(fixture.merchantId()))
            .as("nothing is delivered yet, so no balance exists")
            .isEmpty();

        drain();

        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", ORDER_AMOUNT_MINOR));
    }

    /**
     * ONE EVENT, TWO CONSUMERS, NEITHER AWARE OF THE OTHER. Order moves its status and the Ledger
     * posts its journal, from a single {@code payment.succeeded}.
     * <p>
     * This is what {@code processed_events} leading its primary key with the consumer name buys
     * (V14). Keyed on the event id alone, whichever consumer ran first would have starved the other
     * -- and the symptom would have been "the Ledger never posts", days later, with nothing in any
     * log to say why.
     * <p>
     * <b>Sabotage that must turn this red:</b> give {@code PaymentSucceededLedgerHandler} the same
     * {@code consumerName()} as Order's handler. The dispatcher refuses the duplicate at startup, so
     * this fails loudly -- which is itself the guard working.
     */
    @Test
    void feedsBothOrderAndTheLedgerFromTheSameEvent() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        drain();

        assertThat(orderStatus(fixture)).isEqualTo(OrderStatus.PAID);
        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", ORDER_AMOUNT_MINOR));
    }

    /**
     * The balance follows the CAPTURE, not the order's amount. A partial capture of 3000 against a
     * 4000 order leaves the merchant owed 3000 -- the order is PARTIALLY_PAID and the ledger says
     * the same thing in money.
     */
    @Test
    void postsOnlyWhatWasActuallyCaptured() {
        Fixture fixture = authorizedIntent();

        capturePaymentIntentService.capture(fixture.merchantId(), fixture.intentId(), 3000L);
        drain();

        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", 3000L));
    }

    /** Two payments accumulate; a balance is a sum over entries, not a last-write-wins column. */
    @Test
    void accumulatesAcrossPayments() {
        MerchantId merchantId = existingMerchant();

        capture(merchantId, ORDER_AMOUNT_MINOR);
        capture(merchantId, 2500L);
        drain();

        assertThat(pendingBalance(merchantId))
            .containsExactly(new MerchantBalance("INR", ORDER_AMOUNT_MINOR + 2500L));
    }

    /** One merchant cannot see another's money. The balance query is scoped, not filtered. */
    @Test
    void scopesTheBalanceToTheMerchantThatEarnedIt() {
        Fixture paid = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(paid, ORDER_AMOUNT_MINOR));
        drain();

        MerchantId stranger = existingMerchant();

        assertThat(pendingBalance(stranger))
            .as("a merchant with no payments has an empty balance, not somebody else's")
            .isEmpty();
    }

    /**
     * REDELIVERY POSTS NOTHING EXTRA. The event is un-stamped between passes, which is exactly what
     * a crash between a handler's commit and the {@code published_at} stamp leaves behind.
     * <p>
     * Two mechanisms independently prevent the double-post here -- the inbox row and
     * {@code uq_ledger_transactions_idempotency} -- so this is a whole-path assertion rather than a
     * test of either one. The unique key is isolated by
     * {@link #refusesASecondJournalForTheSamePaymentEvenFromRawSql} below.
     */
    @Test
    void postsOnceWhenTheSameEventIsDeliveredTwice() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        drain();
        unpublish(fixture);
        drain();

        assertThat(journalCount(fixture.merchantId())).isEqualTo(1);
        assertThat(pendingBalance(fixture.merchantId()))
            .containsExactly(new MerchantBalance("INR", ORDER_AMOUNT_MINOR));
    }

    // --- WHAT THE DATABASE REFUSES ----------------------------------------------------------------

    /**
     * THE INVARIANT THE WHOLE MODULE EXISTS FOR, with the application entirely out of the path.
     * <p>
     * A journal is inserted by hand with a 4000 debit and a 3000 credit. {@link
     * com.paymesh.ledger.domain.LedgerTransaction} would have refused it; nothing here consults
     * {@code LedgerTransaction}. The trigger is deferred, so both INSERTs succeed and the failure
     * arrives at COMMIT -- which is the correct moment, because a journal is only balanced once all
     * of its entries exist.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop {@code tr_ledger_entries_balanced} from V15.
     * Every Java-level test stays green.
     */
    @Test
    void refusesAnUnbalancedJournalAtCommitEvenFromRawSql() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "unbalanced-" + UUID.randomUUID());

            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            insertEntry(transactionId, openAccount(merchantId, "MERCHANT_PENDING"), "CREDIT", 3000);

            return null;
        }))
            .hasStackTraceContaining("unbalanced");
    }

    /**
     * A ONE-SIDED JOURNAL, refused by the entry COUNT rather than by the sum.
     * <p>
     * The sum would catch this one too -- 4000 debits against 0 credits is unbalanced -- but the
     * count runs first and gives the more precise message. The count is not redundant with the sum:
     * it is the only thing that would refuse a journal whose entries all cancel out to nothing on
     * both sides, and it is why a genuinely empty header can never be called balanced.
     */
    @Test
    void refusesAJournalWithASingleEntryAtCommit() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "one-sided-" + UUID.randomUUID());

            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);

            return null;
        }))
            .hasStackTraceContaining("a journal needs at least two");
    }

    /**
     * A JOURNAL CANNOT BE HEADED FOR ONE MERCHANT AND MOVE ANOTHER'S MONEY.
     * <p>
     * Found by code review on this branch, and it committed cleanly before the check existed. The
     * damage is subtle enough to be worth spelling out: the balance query attributes money by the
     * ACCOUNT's owner, so the money lands on the merchant whose account was credited and the
     * balance is arithmetically right. What breaks is the audit trail —
     * {@code ledger_transactions.merchant_id} then names somebody else, and "everything posted for
     * this merchant" returns a set that does not reconcile against that merchant's own balance.
     * <p>
     * The composite tenant foreign keys that do this job in V5/V6/V8 cannot do it here: platform
     * accounts carry a NULL {@code merchant_id} and a composite key containing a NULL matches
     * nothing. Hence the check inside the deferred trigger.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code foreign_owner} block from
     * {@code ledger_assert_balanced}. Every other test stays green, including every Java one.
     */
    @Test
    void refusesAJournalThatMovesADifferentMerchantsMoney() {
        MerchantId headerMerchant = existingMerchant();
        MerchantId accountOwner = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId =
                insertJournalHeader(headerMerchant, "cross-tenant-" + UUID.randomUUID());

            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            insertEntry(
                transactionId, openAccount(accountOwner, "MERCHANT_PENDING"), "CREDIT", 4000
            );

            return null;
        }))
            .hasStackTraceContaining("moves");

        assertThat(pendingBalance(accountOwner))
            .as("and the money never reached the account it was aimed at")
            .isEmpty();
    }

    /**
     * SDD 15.6 INVARIANT 5, ENFORCED RATHER THAN DOCUMENTED. A correction is a new reversal
     * transaction, never an edit -- and this trigger is what makes the reversal the only available
     * option when Refund arrives, rather than the disciplined one.
     */
    @Test
    void refusesToUpdateAPostedEntry() {
        Fixture fixture = postedJournal();

        assertThatThrownBy(() -> jdbc
            .sql("update ledger_entries set amount_minor = 1 where ledger_transaction_id = ?")
            .param(fixture.ledgerTransactionId())
            .update())
            .hasStackTraceContaining("immutable");
    }

    @Test
    void refusesToDeleteAPostedEntry() {
        Fixture fixture = postedJournal();

        assertThatThrownBy(() -> jdbc
            .sql("delete from ledger_entries where ledger_transaction_id = ?")
            .param(fixture.ledgerTransactionId())
            .update())
            .hasStackTraceContaining("immutable");
    }

    /** The header too -- re-pointing a posted journal rewrites history as surely as editing a line. */
    @Test
    void refusesToUpdateAPostedJournalHeader() {
        Fixture fixture = postedJournal();

        assertThatThrownBy(() -> jdbc
            .sql("update ledger_transactions set reference_id = 'pi_other' "
                + "where ledger_transaction_id = ?")
            .param(fixture.ledgerTransactionId())
            .update())
            .hasStackTraceContaining("immutable");
    }

    /**
     * THE IDEMPOTENCY KEY IN ISOLATION, which the redelivery test above cannot prove because the
     * inbox stops the second delivery before the key is ever consulted.
     * <p>
     * Here the key is reused directly. What is being pinned is that the guard is keyed on the
     * PAYMENT rather than on the event: two different events describing one capture collide, and the
     * second is refused.
     */
    @Test
    void refusesASecondJournalForTheSamePaymentEvenFromRawSql() {
        MerchantId merchantId = existingMerchant();
        String key = "payment-captured:pi_" + UUID.randomUUID();

        transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, key);
            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", 4000);
            insertEntry(transactionId, openAccount(merchantId, "MERCHANT_PENDING"), "CREDIT", 4000);
            return null;
        });

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            insertJournalHeader(merchantId, key);
            return null;
        }))
            .hasStackTraceContaining("uq_ledger_transactions_idempotency");
    }

    /**
     * SINGLE CURRENCY, ENFORCED BY THE SHAPE OF THE FOREIGN KEY. An entry cannot name a transaction
     * in INR and an account in USD, because the key it points through carries the currency.
     * <p>
     * This is the same trick as the composite tenant foreign keys in V5/V6/V8: the wrong row is not
     * detected afterwards, it cannot be inserted.
     */
    @Test
    void refusesAnEntryWhoseCurrencyDoesNotMatchItsAccount() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "mixed-" + UUID.randomUUID());
            String usdAccount = openAccount(merchantId, "MERCHANT_PENDING", "USD");

            // The header is INR, so the entry is INR, but the account is denominated in USD.
            insertEntry(transactionId, usdAccount, "CREDIT", 4000);

            return null;
        }))
            .hasStackTraceContaining("fk_ledger_entries_account");
    }

    /** A negative or zero amount is not a direction, it is a corrupt row. */
    @Test
    void refusesANonPositiveEntryAmount() {
        MerchantId merchantId = existingMerchant();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            String transactionId = insertJournalHeader(merchantId, "negative-" + UUID.randomUUID());
            insertEntry(transactionId, openAccount(null, "PROVIDER_CLEARING"), "DEBIT", -4000);
            return null;
        }))
            .hasStackTraceContaining("ck_ledger_entries_amount_positive");
    }

    /**
     * A merchant account with no merchant would be posted to correctly and then never appear in any
     * balance -- money in the ledger and invisible, with nothing reading as an error.
     */
    @Test
    void refusesAMerchantAccountWithNoMerchant() {
        assertThatThrownBy(() -> jdbc
            .sql("""
                insert into ledger_accounts (ledger_account_id, account_reference, merchant_id,
                                             account_type, currency, normal_balance, created_at)
                values (?, ?, null, 'MERCHANT_PENDING', 'INR', 'CREDIT', ?)
                """)
            .params(
                "lac_" + UUID.randomUUID(),
                "merchant:orphan:pending:INR",
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update())
            .hasStackTraceContaining("ck_ledger_accounts_owner");
    }

    /** Two accounts at one address would each hold part of a balance, with no error anywhere. */
    @Test
    void refusesTwoAccountsAtTheSameReference() {
        MerchantId merchantId = existingMerchant();
        openAccount(merchantId, "MERCHANT_PENDING");

        assertThatThrownBy(() -> jdbc
            .sql("""
                insert into ledger_accounts (ledger_account_id, account_reference, merchant_id,
                                             account_type, currency, normal_balance, created_at)
                values (?, ?, ?, 'MERCHANT_PENDING', 'INR', 'CREDIT', ?)
                """)
            .params(
                "lac_" + UUID.randomUUID(),
                "merchant:" + merchantId.value() + ":pending:INR",
                merchantId.value(),
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update())
            .hasStackTraceContaining("uq_ledger_accounts_reference");
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Fixture(
        MerchantId merchantId,
        OrderId orderId,
        PaymentIntentId intentId,
        String ledgerTransactionId
    ) {
    }

    /**
     * Passes until nothing more moves. The suite shares one container, so a single pass can be
     * entirely consumed by the backlog of every other integration test that ever created an order.
     * See {@code EventDeliveryIntegrationTest.drain} for the full reasoning.
     */
    private void drain() {
        while (relay.publish().published() > 0) {
            // keep going
        }
    }

    private List<MerchantBalance> pendingBalance(MerchantId merchantId) {
        return balances.forMerchant(merchantId);
    }

    private Fixture confirmedIntent(CaptureMethod captureMethod) {
        return confirmedIntent(existingMerchant(), captureMethod, ORDER_AMOUNT_MINOR);
    }

    private Fixture confirmedIntent(
        MerchantId merchantId,
        CaptureMethod captureMethod,
        long amountMinor
    ) {
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            amountMinor, "INR", null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, amountMinor, "INR", captureMethod,
            null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return new Fixture(merchantId, order.orderId(), intent.paymentIntentId(), null);
    }

    /** Confirmed, then authorized, so a MANUAL capture has something to collect. */
    private Fixture authorizedIntent() {
        Fixture fixture = confirmedIntent(existingMerchant(), CaptureMethod.MANUAL, ORDER_AMOUNT_MINOR);

        callbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "evt-authorize-" + UUID.randomUUID(), PROVIDER_EVENT, fixture.intentId().value(),
                null, ProviderOutcome.AUTHORIZED, ORDER_AMOUNT_MINOR, null, null, null, null
            ),
            payloadHash()
        ));

        return fixture;
    }

    /** A whole successful capture for one merchant, without draining. */
    private void capture(MerchantId merchantId, long amountMinor) {
        Fixture fixture = confirmedIntent(merchantId, CaptureMethod.AUTOMATIC, amountMinor);

        callbacks.record(succeeded(fixture, amountMinor));
    }

    /** A journal posted through the real path, so the immutability tests have a genuine row. */
    private Fixture postedJournal() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        drain();

        String transactionId = jdbc
            .sql("select ledger_transaction_id from ledger_transactions where merchant_id = ?")
            .param(fixture.merchantId().value())
            .query(String.class)
            .single();

        return new Fixture(
            fixture.merchantId(), fixture.orderId(), fixture.intentId(), transactionId
        );
    }

    private static RecordProviderCallbackCommand succeeded(Fixture fixture, long capturedAmount) {
        return new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "evt-succeed-" + UUID.randomUUID(), PROVIDER_EVENT, fixture.intentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, capturedAmount, null, null, null
            ),
            payloadHash()
        );
    }

    private static String payloadHash() {
        return (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
    }

    private OrderStatus orderStatus(Fixture fixture) {
        return orders.findByOrderId(fixture.merchantId(), fixture.orderId()).orElseThrow().status();
    }

    private long journalCount(MerchantId merchantId) {
        return jdbc.sql("select count(*) from ledger_transactions where merchant_id = ?")
            .param(merchantId.value())
            .query(Long.class)
            .single();
    }

    private void unpublish(Fixture fixture) {
        jdbc.sql("update outbox_events set published_at = null where merchant_id = ?")
            .param(fixture.merchantId().value())
            .update();
    }

    // --- raw SQL, going round the application entirely ---------------------------------------------

    private String insertJournalHeader(MerchantId merchantId, String idempotencyKey) {
        String transactionId = "ltx_" + UUID.randomUUID();

        jdbc.sql("""
                insert into ledger_transactions (ledger_transaction_id, merchant_id, transaction_type,
                                                 reference_type, reference_id, currency,
                                                 idempotency_key, occurred_at, created_at)
                values (?, ?, 'PAYMENT_CAPTURED', 'PAYMENT_INTENT', ?, 'INR', ?, ?, ?)
                """)
            .params(
                transactionId,
                merchantId.value(),
                "pi_" + UUID.randomUUID(),
                idempotencyKey,
                // OffsetDateTime, not Instant: the JDBC driver cannot infer a SQL type for the
                // latter. Hibernate can, which is why the application's own writes do not need this.
                PROVIDER_EVENT.atOffset(ZoneOffset.UTC),
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update();

        return transactionId;
    }

    private void insertEntry(
        String transactionId,
        String accountId,
        String direction,
        long amountMinor
    ) {
        jdbc.sql("""
                insert into ledger_entries (ledger_transaction_id, ledger_account_id, direction,
                                            amount_minor, currency, created_at)
                values (?, ?, ?, ?, 'INR', ?)
                """)
            .params(
                transactionId, accountId, direction, amountMinor,
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update();
    }

    private String openAccount(MerchantId merchantId, String accountType) {
        return openAccount(merchantId, accountType, "INR");
    }

    /** Insert-if-absent, mirroring what the adapter does, so repeated calls are safe. */
    private String openAccount(MerchantId merchantId, String accountType, String currency) {
        String reference = merchantId == null
            ? "provider-clearing:" + currency
            : "merchant:" + merchantId.value() + ":pending:" + currency;

        String normalBalance = merchantId == null ? "DEBIT" : "CREDIT";

        jdbc.sql("""
                insert into ledger_accounts (ledger_account_id, account_reference, merchant_id,
                                             account_type, currency, normal_balance, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (account_reference) do nothing
                """)
            .params(
                "lac_" + UUID.randomUUID(),
                reference,
                merchantId == null ? null : merchantId.value(),
                accountType,
                currency,
                normalBalance,
                CREATED_AT.atOffset(ZoneOffset.UTC)
            )
            .update();

        return jdbc
            .sql("select ledger_account_id from ledger_accounts where account_reference = ?")
            .param(reference)
            .query(String.class)
            .single();
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Ledger Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();
    }
}
