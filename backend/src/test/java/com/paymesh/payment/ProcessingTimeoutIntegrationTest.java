package com.paymesh.payment;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.application.TimeOutProcessingPaymentsService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.order.application.PaymentActivityLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PROCESSING timeout against a real PostgreSQL (ADR-015).
 * <p>
 * This is the exit that state has never had. Cancel is refused from PROCESSING by design, so a lost
 * callback used to strand the intent -- and because an intent holds its order's only live slot
 * (ADR-011), the order with it. Recovery was manual.
 * <p>
 * <b>Everything here is money-adjacent, and the two tests that matter are opposites:</b> the timeout
 * must fire on a genuinely stranded intent, and it must NOT fire early. Firing early records a
 * payment that may really have succeeded as failed, releases the slot, and lets the merchant collect
 * twice.
 * <p>
 * Deliberately NOT {@code @Transactional}: the sweep opens a transaction per intent and its
 * idempotency turns on what the first run committed. Each test registers its own merchant and scopes
 * its queries to it, and assertions are per-merchant rather than on the platform-wide sweep counts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ProcessingTimeoutIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Duration AGE = Duration.ofHours(1);
    private static final long ORDER_AMOUNT_MINOR = 1999;
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private GetPaymentIntentService getPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbacks;

    @Autowired
    private PaymentIntentRepository paymentIntents;

    @Autowired
    private PaymentStateHistoryRepository history;

    @Autowired
    private PaymentActivityLookup slot;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    // --- it fires ---------------------------------------------------------------------------

    /**
     * THE HOLE THIS CLOSES, END TO END. A confirmed intent whose callback never arrives is moved to
     * FAILED with a code that says the provider never answered -- and one timeline row and one event
     * record it.
     */
    @Test
    void failsAnIntentStrandedInProcessingBeyondTheAge() {
        Fixture fixture = processing();

        sweeperAt(now(fixture).plus(AGE).plusSeconds(60)).sweep();

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.FAILED);

        assertThat(row(fixture))
            .containsEntry("status", "FAILED")
            .containsEntry("failure_code", "provider_no_response");

        assertThat(transitions(fixture)).endsWith("PROCESSING->FAILED");
        assertThat(actorOfTheLastTransition(fixture))
            .as("SYSTEM, not PROVIDER -- no provider said anything, and the audit trail must not "
                + "claim one did")
            .isEqualTo("SYSTEM");
        assertThat(eventTypes(fixture)).endsWith("payment.failed");
    }

    /**
     * THE OPERATIONAL POINT: FAILED releases the order's slot, so the merchant can start a fresh
     * collection. A timeout that did not release it would solve nothing -- the order would still be
     * dead, just with a differently-worded intent attached to it.
     */
    @Test
    void releasesTheOrdersSlotSoTheMerchantCanTryAgain() {
        Fixture fixture = processing();

        assertThat(slot.hasLivePaymentIntent(fixture.merchantId(), fixture.orderId())).isTrue();

        sweeperAt(now(fixture).plus(AGE).plusSeconds(60)).sweep();

        assertThat(slot.hasLivePaymentIntent(fixture.merchantId(), fixture.orderId()))
            .as("FAILED is one of the two statuses uq_payment_intents_live_per_order excludes")
            .isFalse();

        PaymentIntent second = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            fixture.merchantId(), fixture.orderId(), null, ORDER_AMOUNT_MINOR, "INR",
            null, null, Map.of()
        ));

        assertThat(second.status()).isEqualTo(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD);
    }

    // --- it does not fire early --------------------------------------------------------------

    /**
     * THE GUARD ON THE BELIEF, AND THE MOST IMPORTANT TEST IN THIS CLASS.
     * <p>
     * An intent one second short of the age is a payment the provider may be about to answer for.
     * Failing it says the collection did not happen when it may well have -- and because FAILED
     * releases the slot, the merchant can then create a second intent and take the money twice. The
     * generous age is the ONLY thing standing between this design and that outcome, because
     * reconciliation does not exist (SDD 21.4, 24.1).
     * <p>
     * <b>Sabotage that must turn this red:</b> pass {@code now} instead of {@code now.minus(age)} to
     * {@code findStrandedInProcessing} in {@code TimeOutProcessingPaymentsService.sweep}, or drop the
     * {@code intent.updatedAt().isAfter(cutoff)} half of the re-check under the lock. Either fires on
     * an intent that has barely started.
     */
    @Test
    void doesNotFireBeforeTheAgeHasElapsed() {
        Fixture fixture = processing();

        sweeperAt(now(fixture).plus(AGE).minusSeconds(1)).sweep();

        assertThat(statusOf(fixture))
            .as("a payment the provider may still be answering for must not be declared failed")
            .isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(transitions(fixture)).doesNotContain("PROCESSING->FAILED");
        assertThat(eventTypes(fixture)).doesNotContain("payment.failed");
    }

    /**
     * A RE-CONFIRM RESTARTS THE WAIT, and this is the case a naive implementation gets wrong.
     * <p>
     * The customer abandoned a 3DS challenge for over an hour and then completed it, so the merchant
     * confirmed again. The intent is back in PROCESSING with a FRESH {@code updated_at} -- and timing
     * it out against the first confirm's clock, or against the intent's {@code created_at}, or
     * against attempt 1, would fail a collection that had only just been asked for.
     * <p>
     * The long wait is produced by backdating the row rather than by sleeping. That is the only
     * honest way to reach this case: the two confirms are milliseconds apart in real time, so without
     * it the test would be indistinguishable from {@code doesNotFireBeforeTheAgeHasElapsed} and would
     * pass with the column choice wrong.
     * <p>
     * <b>Sabotage that must turn this red:</b> measure the age from {@code created_at} instead of
     * {@code updated_at} in {@code SpringDataPaymentIntentRepository.findStrandedInProcessing}.
     */
    @Test
    void doesNotFireOnAnIntentWhoseWaitWasRestartedByAReConfirm() {
        Fixture fixture = processing();
        Instant firstConfirm = now(fixture);
        Instant longAgo = firstConfirm.minus(AGE).minusSeconds(600);

        assertThat(deliver(fixture, ProviderOutcome.REQUIRES_ACTION, longAgo.plusSeconds(30)))
            .isEqualTo(ProviderCallbackOutcome.APPLIED);

        // The customer sat on the challenge for well over the timeout. Backdated, because an hour
        // cannot be waited for in a test -- and because created_at is backdated too, an
        // implementation reading THAT column instead would fire here.
        backdate(fixture, longAgo);

        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            fixture.merchantId(), fixture.intentId(), null, null
        ));
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);

        // Beyond the age measured from the abandoned challenge; not beyond it measured from the
        // re-confirm, which happened a moment ago.
        sweeperAt(longAgo.plus(AGE).plusSeconds(300)).sweep();

        assertThat(statusOf(fixture))
            .as("a wait that has just been restarted is not a stranded payment")
            .isEqualTo(PaymentIntentStatus.PROCESSING);
    }

    /** Only PROCESSING times out. An old AUTHORIZED intent has a merchant-driven exit of its own. */
    @Test
    void leavesAnOldAuthorizedIntentAlone() {
        Fixture fixture = processing();
        deliver(fixture, ProviderOutcome.AUTHORIZED, now(fixture).plusSeconds(30));

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.AUTHORIZED);

        sweeperAt(now(fixture).plus(AGE).plusSeconds(999_999)).sweep();

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.AUTHORIZED);
    }

    // --- idempotency ----------------------------------------------------------------------------

    /** Running twice writes nothing twice: the re-check under the lock finds FAILED and stops. */
    @Test
    void writesNothingASecondTimeWhenTheSweepRunsTwice() {
        Fixture fixture = processing();
        TimeOutProcessingPaymentsService sweeper = sweeperAt(now(fixture).plus(AGE).plusSeconds(60));

        sweeper.sweep();
        sweeper.sweep();

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(transitions(fixture)).containsOnlyOnce("PROCESSING->FAILED");
        assertThat(eventTypes(fixture)).containsOnlyOnce("payment.failed");
    }

    // --- what the timeout leaves behind for reconciliation ------------------------------------------

    /**
     * THE EVIDENCE THAT THE BELIEF MAY HAVE BEEN WRONG, AND IT IS DELIBERATELY UNTOUCHED.
     * <p>
     * The attempt row keeps its {@code provider_reference} -- what a reconciler matches against the
     * provider's own file -- and, critically, its {@code last_provider_event_at} is left NULL. That
     * column is ADR-012's monotonic ordering guard: writing a synthetic timestamp into it would make
     * a genuine late callback judge itself STALE and vanish, destroying the only trace that the
     * timeout got it wrong.
     * <p>
     * <b>Sabotage that must turn this red:</b> have the timeout record a provider event on the
     * attempt.
     */
    @Test
    void leavesTheAttemptRowUntouchedSoALateCallbackCanStillBeJudged() {
        Fixture fixture = processing();

        sweeperAt(now(fixture).plus(AGE).plusSeconds(60)).sweep();

        Map<String, Object> attempt = jdbc.queryForList("""
            select status, provider_reference, last_provider_event_at
              from payment_attempts
             where merchant_id = ?
            """, fixture.merchantId().value()).get(0);

        assertThat(attempt)
            .as("the attempt records what PayMesh asked for; the timeout is a belief about the "
                + "intent, not a report from the provider")
            .containsEntry("status", "PROCESSING")
            .containsEntry("last_provider_event_at", null);
    }

    /**
     * THE DIVERGENCE, RECORDED. The provider really did collect and its callback arrives after the
     * timeout. It lands on a FAILED intent, is refused as IGNORED_TERMINAL, moves no money -- and is
     * STORED, which is the whole point. That row is what a reconciliation job would find.
     * <p>
     * This test is ADR-015's residue made concrete: PayMesh believes the payment failed, the provider
     * believes it succeeded, and nothing in this codebase resolves the disagreement. What it does do
     * is make it findable rather than invisible.
     */
    @Test
    void recordsALateSuccessCallbackAgainstATimedOutIntentAsADivergence() {
        Fixture fixture = processing();
        Instant confirmedAt = now(fixture);

        sweeperAt(confirmedAt.plus(AGE).plusSeconds(60)).sweep();
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.FAILED);

        ProviderCallbackOutcome outcome =
            deliver(fixture, ProviderOutcome.SUCCEEDED, confirmedAt.plus(AGE).plusSeconds(120));

        assertThat(outcome)
            .as("a terminal intent absorbs the callback rather than being dragged back to SUCCEEDED")
            .isEqualTo(ProviderCallbackOutcome.IGNORED_TERMINAL);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(eventTypes(fixture))
            .as("no payment.succeeded may exist for an intent PayMesh recorded as failed")
            .doesNotContain("payment.succeeded");

        assertThat(jdbc.queryForList("""
            select outcome from provider_callbacks where merchant_id = ?
            """, String.class, fixture.merchantId().value()))
            .as("the disagreement is on the record, findable by outcome, for a reconciler that "
                + "does not exist yet")
            .containsExactly("IGNORED_TERMINAL");
    }

    // --- the transaction boundary -----------------------------------------------------------------

    /**
     * THE TRANSACTION BOUNDARY, from the far end. The intent is moved and its timeline row written
     * when the outbox append throws. If the three are not in one transaction they commit, and this
     * test finds a payment marked failed that nothing ever announced.
     */
    @Test
    void leavesTheIntentProcessingWhenTheOutboxAppendFails() {
        Fixture fixture = processing();

        TimeOutProcessingPaymentsService sabotaged = new TimeOutProcessingPaymentsService(
            paymentIntents,
            history,
            event -> {
                throw new IllegalStateException("outbox is down");
            },
            transactionTemplate,
            Clock.fixed(now(fixture).plus(AGE).plusSeconds(60), ZoneOffset.UTC),
            AGE,
            100
        );

        assertThat(sabotaged.sweep().errored())
            .as("counted and logged, not swallowed and not fatal to the sweep")
            .isPositive();
        assertThat(statusOf(fixture))
            .as("no payment may be marked failed without its event")
            .isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(transitions(fixture)).doesNotContain("PROCESSING->FAILED");
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** The production service with the clock moved, which is the only way to make time pass here. */
    private TimeOutProcessingPaymentsService sweeperAt(Instant now) {
        return new TimeOutProcessingPaymentsService(
            paymentIntents, history, outbox, transactionTemplate,
            Clock.fixed(now, ZoneOffset.UTC), AGE, 100
        );
    }

    /**
     * When the intent last entered PROCESSING, read back off the row.
     * <p>
     * Read rather than assumed because the confirm stamped it from the application's REAL clock, and
     * that is the same column the sweep's cutoff is compared against. Hard-coding an instant here
     * would test the sweep against a timestamp the row does not have.
     */
    private Instant now(Fixture fixture) {
        return getPaymentIntentService.getById(fixture.merchantId(), fixture.intentId()).updatedAt();
    }

    /** A merchant, an order, an intent with a method attached and confirmed: PROCESSING, attempt 1. */
    private Fixture processing() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();

        String orderId = orders.save(Order.create(
            OrderId.generate(), merchantId, null, null, ORDER_AMOUNT_MINOR, "INR", null,
            Map.of(), null, CREATED_AT
        )).orderId().value();

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, orderId, null, ORDER_AMOUNT_MINOR, "INR", null, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return new Fixture(merchantId, intent.paymentIntentId(), orderId);
    }

    private ProviderCallbackOutcome deliver(
        Fixture fixture,
        ProviderOutcome outcome,
        Instant occurredAt
    ) {
        String eventId = "sim_evt_" + UUID.randomUUID();

        return callbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                eventId,
                occurredAt,
                fixture.intentId().value(),
                "sim_pay_" + UUID.randomUUID(),
                outcome,
                outcome == ProviderOutcome.AUTHORIZED ? ORDER_AMOUNT_MINOR : null,
                outcome == ProviderOutcome.SUCCEEDED ? ORDER_AMOUNT_MINOR : null,
                null,
                null,
                outcome == ProviderOutcome.REQUIRES_ACTION
                    ? "https://3ds.simulator.test/challenge/abc"
                    : null
            ),
            hashOf(eventId)
        ));
    }

    private PaymentIntentStatus statusOf(Fixture fixture) {
        return getPaymentIntentService.getById(fixture.merchantId(), fixture.intentId()).status();
    }

    /**
     * Ages a row by rewriting its timestamps, because an hour cannot be waited for.
     * <p>
     * BOTH {@code created_at} AND {@code updated_at}, and the attempt's {@code created_at} with them.
     * Moving only the one the current implementation happens to read would make the test agree with
     * the code by construction; moving all three means a sweep that measured age from any of them
     * would fire, and the test can then prove which one is correct.
     */
    private void backdate(Fixture fixture, Instant to) {
        jdbc.update(
            "update payment_intents set created_at = ?, updated_at = ? where payment_intent_id = ?",
            java.sql.Timestamp.from(to), java.sql.Timestamp.from(to), fixture.intentId().value()
        );
        jdbc.update(
            "update payment_attempts set created_at = ?, updated_at = ? where merchant_id = ?",
            java.sql.Timestamp.from(to), java.sql.Timestamp.from(to), fixture.merchantId().value()
        );
    }

    private Map<String, Object> row(Fixture fixture) {
        return jdbc.queryForList("""
            select status, failure_code, failure_message, captured_amount_minor
              from payment_intents
             where payment_intent_id = ?
            """, fixture.intentId().value()).get(0);
    }

    private List<String> transitions(Fixture fixture) {
        return jdbc.queryForList("""
            select coalesce(from_status, 'null') || '->' || to_status as move
              from payment_state_history
             where merchant_id = ?
             order by occurred_at, payment_state_history_id
            """, String.class, fixture.merchantId().value());
    }

    private String actorOfTheLastTransition(Fixture fixture) {
        return jdbc.queryForList("""
            select actor_type from payment_state_history
             where merchant_id = ?
             order by occurred_at desc, payment_state_history_id desc
             limit 1
            """, String.class, fixture.merchantId().value()).get(0);
    }

    private List<String> eventTypes(Fixture fixture) {
        return jdbc.queryForList(
            "select event_type from outbox_events where merchant_id = ? order by occurred_at, event_id",
            String.class,
            fixture.merchantId().value()
        );
    }

    /** Stands in for the signature filter's hash of the raw body: 64 hex characters, distinct. */
    private static String hashOf(String seed) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Fixture(MerchantId merchantId, PaymentIntentId intentId, String orderId) {
    }
}
