package com.paymesh.payment;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.payment.application.AttachPaymentMethodService;
import com.paymesh.payment.application.CancelPaymentIntentService;
import com.paymesh.payment.application.ConfirmPaymentIntentCommand;
import com.paymesh.payment.application.ConfirmPaymentIntentService;
import com.paymesh.payment.application.CreatePaymentIntentCommand;
import com.paymesh.payment.application.CreatePaymentIntentService;
import com.paymesh.payment.application.GetPaymentIntentService;
import com.paymesh.payment.application.PaymentAttemptRepository;
import com.paymesh.payment.application.PaymentIntentNotFoundException;
import com.paymesh.payment.application.PaymentIntentRepository;
import com.paymesh.payment.application.PaymentStateHistoryRepository;
import com.paymesh.payment.application.ProviderCallbackRepository;
import com.paymesh.payment.application.RecordProviderCallbackCommand;
import com.paymesh.payment.application.RecordProviderCallbackService;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * THE THREE MECHANISMS OF ADR-012, AGAINST A REAL POSTGRESQL. Each test here should go red if the
 * mechanism it names is removed, and the sabotage that proves it is written in the test's comment.
 * <p>
 * Deliberately NOT {@code @Transactional}. Two of these tests turn on what a ROLLBACK leaves
 * behind and two more need a second thread to see committed rows; an outer test transaction would
 * make all four pass whether or not the code was right, which is the exact failure this class exists
 * to prevent. Every test registers its own merchant and scopes its queries to it, so rows surviving
 * between tests cannot make one test's assertions depend on another's.
 * <p>
 * The HTTP contract -- the HMAC, the status codes -- is {@code ProviderCallbackApiTest}'s job.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ProviderCallbackIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-01T10:15:30Z");
    private static final Instant FIRST_EVENT = Instant.parse("2026-08-01T11:00:00Z");
    private static final long ORDER_AMOUNT_MINOR = 1999;
    private static final String PROVIDER = "SIMULATOR";

    /**
     * Long enough for a blocked thread to actually reach the row it blocks on, short enough that a
     * genuine deadlock fails the suite instead of hanging it.
     */
    private static final Duration UNTIL_BLOCKED = Duration.ofMillis(500);

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private CancelPaymentIntentService cancelPaymentIntentService;

    @Autowired
    private GetPaymentIntentService getPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbackService;

    @Autowired
    private PaymentIntentRepository paymentIntents;

    @Autowired
    private PaymentAttemptRepository attempts;

    @Autowired
    private PaymentStateHistoryRepository history;

    @Autowired
    private ProviderCallbackRepository callbacks;

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private Clock clock;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcTemplate jdbc;

    // --- the happy path, counted -------------------------------------------------

    /**
     * A SUCCEEDED callback's whole footprint: the intent moves, the attempt records what the provider
     * said, ONE timeline row, ONE event, ONE callback row. The counts are as load-bearing as the
     * values -- two events for one callback is a payment announced twice.
     * <p>
     * SUCCEEDED here is operational state and nothing else (design section 0.5): no balance moved,
     * no ledger entry exists, and {@code orders.status} is deliberately still PENDING.
     */
    @Test
    void movesTheIntentAndWritesOneOfEachRowWhenAPaymentSucceeds() {
        Fixture fixture = processingIntent();

        ProviderCallbackOutcome outcome = deliver(callbackService, succeeded(fixture, FIRST_EVENT));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.APPLIED);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.SUCCEEDED);

        Map<String, Object> callback = onlyCallbackRow(fixture);
        assertThat(callback)
            .containsEntry("outcome", "APPLIED")
            .containsEntry("provider", PROVIDER)
            .containsEntry("payment_intent_id", fixture.intentId().value())
            .containsEntry("merchant_id", fixture.merchantId().value());

        Map<String, Object> attempt = onlyAttemptRow(fixture);
        assertThat(attempt)
            .containsEntry("status", "SUCCEEDED")
            .containsEntry("provider_reference", fixture.providerReferenceFor("succeeded"))
            // THE MONOTONIC CLOCK, WRITTEN. Everything the ordering guard does later reads this.
            .containsEntry("last_provider_event_at", java.sql.Timestamp.from(FIRST_EVENT));

        assertThat(transitions(fixture))
            .as("create, attach, confirm, then the provider's outcome -- one row each")
            .containsExactly("REQUIRES_CONFIRMATION->PROCESSING", "PROCESSING->SUCCEEDED");
        assertThat(actorOfTheLastTransition(fixture))
            .as("a provider moved it, not the merchant")
            .isEqualTo("PROVIDER");
        assertThat(eventTypes(fixture))
            .containsExactly(
                "payment.created", "payment.method_attached", "payment.processing",
                "payment.succeeded"
            );
        assertThat(capturedAmountOfTheLastEvent(fixture)).isEqualTo("1999");
    }

    /** The other three outcomes reach the states this PR owns, and each announces its own event. */
    @Test
    void reachesAuthorizedFailedAndRequiresActionFromTheirOwnCallbacks() {
        assertThat(deliveredOutcomeOf(ProviderOutcome.AUTHORIZED))
            .isEqualTo(PaymentIntentStatus.AUTHORIZED);
        assertThat(deliveredOutcomeOf(ProviderOutcome.FAILED))
            .isEqualTo(PaymentIntentStatus.FAILED);
        assertThat(deliveredOutcomeOf(ProviderOutcome.REQUIRES_ACTION))
            .isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);
    }

    // --- duplicated ---------------------------------------------------------------

    /**
     * THE DEDUPLICATION, PROVED ON THE ROW COUNTS RATHER THAN THE RESPONSE. Both deliveries answer
     * 200 -- that is the point -- so the response cannot tell the two apart and only the counts can.
     * <p>
     * Sabotage that must turn this red: drop {@code pk_provider_callbacks} from V10.
     */
    @Test
    void appliesOnceWhenTheSameCallbackIsDeliveredTwice() {
        Fixture fixture = processingIntent();
        ProviderEvent event = succeeded(fixture, FIRST_EVENT);

        assertThat(deliver(callbackService, event)).isEqualTo(ProviderCallbackOutcome.APPLIED);
        assertThat(deliver(callbackService, event)).isEqualTo(ProviderCallbackOutcome.DUPLICATE);

        assertThat(callbackCount(fixture)).as("one delivery, one row").isOne();
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(transitions(fixture))
            .as("the duplicate wrote no second transition")
            .containsExactly("REQUIRES_CONFIRMATION->PROCESSING", "PROCESSING->SUCCEEDED");
        assertThat(eventTypes(fixture))
            .as("and announced nothing")
            .containsExactly(
                "payment.created", "payment.method_attached", "payment.processing",
                "payment.succeeded"
            );
    }

    /**
     * THE CONSTRAINT ITSELF, WITH NO JAVA IN THE PATH.
     * <p>
     * The test above goes through Hibernate, which keeps its own map of what it has persisted and
     * will refuse a repeated identifier from inside a session before PostgreSQL is ever asked. That
     * makes it a good test of the OUTCOME and a poor test of the CONSTRAINT -- and section 4.6 is
     * explicit that a duplicate test which passes with the key dropped proves nothing. This insert
     * goes round the application entirely, so what answers is {@code pk_provider_callbacks} and
     * nothing else.
     * <p>
     * It also pins the shape of the key. The second row here differs from the first ONLY in its
     * merchant and its intent: under the house style of a merchant-leading key it would be accepted,
     * and one provider event would be processed once per merchant it resolves against. That is the
     * duplicate the key exists to stop, and it is a duplicate that moves money.
     */
    @Test
    void refusesTwoRawCallbackRowsSharingAProviderAndEventId() {
        Fixture first = processingIntent();
        Fixture second = processingIntent();
        String sharedEventId = eventId();

        assertThat(rawCallback(first, sharedEventId)).isOne();
        assertThat(rawCallback(first, eventId()))
            .as("a different event id is fine -- an intent may have many callbacks")
            .isOne();

        assertThatThrownBy(() -> rawCallback(second, sharedEventId))
            .as("the same provider event, resolved against a second merchant, is still a duplicate")
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("pk_provider_callbacks");

        assertThat(callbackCount(second)).isZero();
    }

    /**
     * THE CASE SECTION 0.4 SAYS REVIEWERS GET WRONG, AND IT IS WHY THE INSERT IS INSIDE THE
     * TRANSACTION.
     * <p>
     * Two identical deliveries land concurrently. The first inserts the callback row and then fails
     * -- its outbox is down -- so its transaction ROLLS BACK and the row goes with it. The second
     * must therefore succeed and apply the event. A reviewer reasoning about an in-transaction
     * insert without the blocking step concludes the event is lost here. It is not.
     * <p>
     * <b>What actually orders these two is the intent's row lock, and the primary key is what
     * decides the second's answer.</b> Two identical callbacks name one intent, so they always
     * contend on the lock first; if the lock were removed they would queue on the index entry
     * instead and reach the same result. Neither mechanism is doing the other's job -- that is the
     * point of having both.
     * <p>
     * Sabotage that must turn this red: commit the callback row in its own transaction, outside the
     * state change. The first delivery's row then survives its rollback, the second is refused as a
     * DUPLICATE, and the payment stays PROCESSING forever with the provider believing it collected.
     */
    @Test
    void appliesTheSecondDeliveryWhenAConcurrentDuplicateRollsBack() {
        Fixture fixture = processingIntent();
        ProviderEvent event = succeeded(fixture, FIRST_EVENT);

        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch mayFail = new CountDownLatch(1);

        RecordProviderCallbackService doomed = serviceWithOutbox(outboxEvent -> {
            // The callback row is in this transaction's session by now; the state change is written
            // and unflushed behind it.
            inserted.countDown();
            await(mayFail);
            throw new IllegalStateException("outbox is down");
        });

        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<Throwable> first = pool.submit(() -> {
                    try {
                        deliver(doomed, event);
                        return null;
                    } catch (RuntimeException failure) {
                        return (Throwable) failure;
                    }
                });

                assertThat(inserted.await(20, TimeUnit.SECONDS))
                    .as("the first delivery reached its outbox append")
                    .isTrue();

                Future<ProviderCallbackOutcome> second =
                    pool.submit(() -> deliver(callbackService, event));

                // Give the second delivery time to reach the row it must wait on. Even if it has
                // not, the assertion below still holds -- it would simply run after the rollback
                // instead of blocking through it -- so this is a sharper test, not a flaky one.
                Thread.sleep(UNTIL_BLOCKED.toMillis());
                mayFail.countDown();

                assertThat(first.get()).isInstanceOf(IllegalStateException.class);
                assertThat(second.get())
                    .as("the first rolled back, so the event was never processed and this one must")
                    .isEqualTo(ProviderCallbackOutcome.APPLIED);
            }
        });

        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(callbackCount(fixture)).as("the rolled-back row is gone").isOne();
        assertThat(transitions(fixture))
            .containsExactly("REQUIRES_CONFIRMATION->PROCESSING", "PROCESSING->SUCCEEDED");
    }

    // --- out of order --------------------------------------------------------------

    /**
     * THE CASE THE STATE MACHINE ALONE CANNOT CATCH, which is the whole reason
     * {@code last_provider_event_at} exists.
     * <p>
     * REQUIRES_ACTION, then a re-confirm back to PROCESSING, then a STALE REQUIRES_ACTION from
     * before the re-confirm. That last transition is PROCESSING to REQUIRES_ACTION -- perfectly
     * legal -- so nothing in the aggregate refuses it and the payment would be dragged backwards
     * into a challenge the customer already completed.
     * <p>
     * Note the re-confirm opens attempt 2, whose own clock is null. The guard reads the MAXIMUM
     * across the intent's attempts for exactly this reason; a per-attempt read passes every other
     * test in this class and fails this one.
     * <p>
     * Sabotage that must turn this red: delete the {@code isStale} comparison, or read the clock
     * from the latest attempt instead of the maximum.
     */
    @Test
    void refusesAStaleEventThatWouldDragThePaymentBackwards() {
        Fixture fixture = processingIntent();
        Instant challenged = FIRST_EVENT;

        assertThat(deliver(callbackService, requiresAction(fixture, challenged)))
            .isEqualTo(ProviderCallbackOutcome.APPLIED);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.REQUIRES_ACTION);

        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            fixture.merchantId(), fixture.intentId(), null, null
        ));
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(attemptCount(fixture)).as("re-confirming opens a second attempt").isEqualTo(2);

        ProviderCallbackOutcome outcome =
            deliver(callbackService, requiresAction(fixture, challenged.minusSeconds(60)));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.IGNORED_STALE);
        assertThat(statusOf(fixture))
            .as("a stale event must never move a payment backwards")
            .isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(callbackCount(fixture))
            .as("refused, and still recorded so a re-delivery is deduplicated too")
            .isEqualTo(2);
    }

    /**
     * THE 3DS PATH A REAL PROVIDER ACTUALLY WALKS, AND THE ONE NOTHING COVERED.
     * <p>
     * A provider issues ONE reference per payment and keeps it: it asks for a challenge on that
     * reference and reports the outcome on the same reference once the customer completes it. But
     * re-confirming opens a SECOND attempt, and
     * {@code uq_payment_attempts_provider_reference} is unique across the whole table, so recording
     * the reference again on that second attempt used to violate the constraint.
     * <p>
     * The consequence was not a tidy error. The insert failed, the transaction rolled back, <b>and
     * the provider_callbacks row rolled back with it</b>, so the event was never deduplicated and the
     * provider retried into the same 500 forever. The intent stranded in PROCESSING, ADR-015's
     * sweeper eventually recorded it FAILED for no response, that released the order's slot, and the
     * merchant could collect a second time for money the customer had already paid.
     * <p>
     * Every other test in this class mints a fresh reference per outcome kind, so none of them ever
     * walked this path -- the one realistic flow was the uncovered one.
     * <p>
     * Sabotage that must turn this red: pass {@code event.providerReference()} straight through in
     * {@code RecordProviderCallbackService.apply} instead of going through
     * {@code referenceToRecord}.
     */
    @Test
    void completesAChallengedPaymentWhenTheProviderKeepsOneReferenceThroughout() {
        Fixture fixture = processingIntent();

        // One reference for one payment, exactly as a provider issues it.
        String reference = fixture.providerReferenceFor("3ds");

        assertThat(deliver(callbackService, new ProviderEvent(
            eventId(), FIRST_EVENT, fixture.intentId().value(), reference,
            ProviderOutcome.REQUIRES_ACTION, null, null, null, null,
            "https://3ds.simulator.test/challenge/abc"
        ))).isEqualTo(ProviderCallbackOutcome.APPLIED);

        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            fixture.merchantId(), fixture.intentId(), null, null
        ));
        assertThat(attemptCount(fixture)).as("re-confirming opens a second attempt").isEqualTo(2);

        ProviderCallbackOutcome outcome = deliver(callbackService, new ProviderEvent(
            eventId(), FIRST_EVENT.plusSeconds(60), fixture.intentId().value(), reference,
            ProviderOutcome.SUCCEEDED, null, ORDER_AMOUNT_MINOR, null, null, null
        ));

        assertThat(outcome)
            .as("the customer completed the challenge; the payment must complete with them")
            .isEqualTo(ProviderCallbackOutcome.APPLIED);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.SUCCEEDED);

        assertThat(jdbc.queryForObject("""
            select count(*) from payment_attempts
             where merchant_id = ? and provider_reference = ?
            """, Long.class, fixture.merchantId().value(), reference))
            .as("the reference names the payment once, on the attempt that first saw it")
            .isEqualTo(1L);
    }

    /**
     * TIES ARE REFUSED, AND ADR-012 SECTION 3 IS HONEST ABOUT WHAT THAT COSTS.
     * <p>
     * Run inside the PROCESSING/REQUIRES_ACTION cycle on purpose. Anywhere else the state machine
     * would refuse the second event too -- every provider outcome leaves PROCESSING, so nothing is
     * legal afterwards -- and the test would pass with the clock deleted. Here the tied event IS a
     * legal transition, so the only thing that can refuse it is the strictly-after comparison.
     * <p>
     * The trade this pins down: two genuinely different events sharing a timestamp mean the second
     * is dropped and the payment strands. That is "never moved forward" chosen over "moved
     * backwards", not the elimination of a failure mode -- a stranded payment is visible and
     * recoverable while a reversed one is neither. The proper fix is a provider sequence number.
     * <p>
     * Sabotage that must turn this red: change {@code !occurredAt.isAfter(lastSeen)} to
     * {@code occurredAt.isBefore(lastSeen)} -- at-or-after instead of strictly after.
     */
    @Test
    void refusesAnEventWhoseTimestampMerelyTiesTheLastOneApplied() {
        Fixture fixture = processingIntent();

        assertThat(deliver(callbackService, requiresAction(fixture, FIRST_EVENT)))
            .isEqualTo(ProviderCallbackOutcome.APPLIED);

        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            fixture.merchantId(), fixture.intentId(), null, null
        ));
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);

        ProviderCallbackOutcome outcome =
            deliver(callbackService, requiresAction(fixture, FIRST_EVENT));

        assertThat(outcome)
            .as("a tie is refused even where the state machine would have allowed it")
            .isEqualTo(ProviderCallbackOutcome.IGNORED_STALE);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);
    }

    /** Terminal states absorb. The second mechanism, and it is not the clock. */
    @Test
    void refusesACallbackForAnIntentThatHasAlreadyFinished() {
        Fixture fixture = processingIntent();

        deliver(callbackService, succeeded(fixture, FIRST_EVENT));

        ProviderCallbackOutcome outcome =
            deliver(callbackService, failed(fixture, FIRST_EVENT.plusSeconds(60)));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.IGNORED_TERMINAL);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(callbackCount(fixture)).isEqualTo(2);
        assertThat(eventTypes(fixture))
            .as("a refused event announces nothing")
            .containsExactly(
                "payment.created", "payment.method_attached", "payment.processing",
                "payment.succeeded"
            );
    }

    /**
     * SECTION 4.4'S ORDERING CASE, AND ITS TEST IS NOT THE HAPPY PATH.
     * <p>
     * The merchant cancels an abandoned 3DS challenge; the customer then completes it and the
     * provider reports SUCCEEDED. PayMesh and the provider now genuinely disagree, and this row --
     * IGNORED_TERMINAL, SUCCEEDED, against a CANCELLED intent -- is the only trace of it.
     * Reconciliation will need to find exactly this, which is why a refused callback is stored at
     * all.
     */
    @Test
    void recordsButRefusesASucceededCallbackForAnIntentTheMerchantCancelled() {
        Fixture fixture = processingIntent();

        deliver(callbackService, requiresAction(fixture, FIRST_EVENT));
        cancelPaymentIntentService.cancel(
            fixture.merchantId(), fixture.intentId(), "challenge abandoned"
        );

        ProviderCallbackOutcome outcome =
            deliver(callbackService, succeeded(fixture, FIRST_EVENT.plusSeconds(60)));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.IGNORED_TERMINAL);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.CANCELLED);

        List<Map<String, Object>> rows = callbackRows(fixture);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1))
            .as("the divergence, findable by outcome")
            .containsEntry("outcome", "IGNORED_TERMINAL");
        assertThat(eventTypes(fixture))
            .as("no payment.succeeded may exist for a cancelled intent")
            .doesNotContain("payment.succeeded");
    }

    /** A provider does not get to change what is owed (SDD 12.3). */
    @Test
    void refusesACallbackClaimingAnAmountTheIntentDoesNotAuthorize() {
        Fixture fixture = processingIntent();

        ProviderCallbackOutcome outcome = deliver(callbackService, new ProviderEvent(
            eventId(), FIRST_EVENT, fixture.intentId().value(),
            fixture.providerReferenceFor("overclaim"), ProviderOutcome.SUCCEEDED,
            null, ORDER_AMOUNT_MINOR + 1, null, null, null
        ));

        assertThat(outcome).isEqualTo(ProviderCallbackOutcome.IGNORED_TERMINAL);
        assertThat(statusOf(fixture)).isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(onlyCallbackRow(fixture))
            .as("the claimed figure is kept, so the disagreement is findable")
            .containsEntry("claimed_captured", String.valueOf(ORDER_AMOUNT_MINOR + 1));
    }

    // --- concurrent and different -----------------------------------------------------

    /**
     * THE PESSIMISTIC LOCK, PROVED ON THE SHAPE OF THE TIMELINE.
     * <p>
     * Two DIFFERENT callbacks for one intent, delivered together. The primary key cannot help --
     * they are different events -- so the only thing between them is the {@code SELECT ... FOR
     * UPDATE} on the intent row. Under it, the loser waits, reads the winner's committed state, and
     * judges itself against the truth.
     * <p>
     * The assertion is that the timeline is a CHAIN: every transition starts where the previous one
     * ended. Without the lock both readers see PROCESSING, both judge themselves legal, and both
     * write {@code from_status = PROCESSING} -- a fork, not a chain -- or one dies on the intent's
     * optimistic version, which the "no failures" assertion catches instead. Asserting only "one
     * applied" would pass in the forked case, so it is deliberately not what is asserted.
     * <p>
     * Sabotage that must turn this red: swap {@code findForProviderCallbackForUpdate} for a
     * non-locking read.
     */
    @Test
    void serializesTwoDifferentCallbacksForOneIntent() throws Exception {
        Fixture fixture = processingIntent();
        ProviderEvent challenge = requiresAction(fixture, FIRST_EVENT);
        ProviderEvent success = succeeded(fixture, FIRST_EVENT.plusSeconds(30));

        List<Object> results = assertTimeoutPreemptively(Duration.ofSeconds(30), () ->
            inParallel(List.of(
                () -> deliverCatching(challenge),
                () -> deliverCatching(success)
            ))
        );

        assertThat(results).as("neither caller may be handed a failure").doesNotHaveAnyElementsOfTypes(
            Throwable.class
        );
        assertThat(callbackCount(fixture)).as("two different events, two rows").isEqualTo(2);

        List<String> timeline = transitions(fixture);
        assertThat(timeline).as("the confirm, plus whichever callbacks applied")
            .hasSizeBetween(2, 3);

        for (int index = 1; index < timeline.size(); index++) {
            assertThat(timeline.get(index))
                .as("every transition must start where the previous one ended")
                .startsWith(timeline.get(index - 1).split("->")[1] + "->");
        }

        assertThat(statusOf(fixture).name())
            .as("and the intent is where the last transition left it")
            .isEqualTo(timeline.get(timeline.size() - 1).split("->")[1]);
    }

    // --- what is never stored, and what is never raw --------------------------------

    /**
     * A callback naming an intent this platform does not know is REJECTED AND STORED NOWHERE. There
     * is nothing to deduplicate for an event that had no effect, and rows keyed on a caller-chosen
     * event id for intents that do not exist are unbounded write amplification on an endpoint
     * reachable with one shared secret.
     */
    @Test
    void storesNothingForACallbackNamingAnIntentThatDoesNotExist() {
        PaymentIntentId ghost = PaymentIntentId.generate();

        assertThatThrownBy(() -> deliver(callbackService, new ProviderEvent(
            eventId(), FIRST_EVENT, ghost.value(), null, ProviderOutcome.SUCCEEDED,
            null, ORDER_AMOUNT_MINOR, null, null, null
        ))).isInstanceOf(PaymentIntentNotFoundException.class);

        assertThat(jdbc.queryForObject(
            "select count(*) from provider_callbacks where payment_intent_id = ?",
            Long.class,
            ghost.value()
        )).isZero();
    }

    /**
     * REDACTION BY ALLOWLIST (SDD 12.6, design section 4.5). The action URL's query string carries a
     * 3DS challenge token and does not survive; only the fields the design names are stored at all.
     * <p>
     * Both destinations are checked. {@code payment_attempts.response_payload} is as durable a record
     * as the callback row and support staff read it too, so redacting only one of them would be no
     * redaction at all.
     */
    @Test
    void storesTheProviderPayloadOnlyAfterRedaction() {
        Fixture fixture = processingIntent();

        deliver(callbackService, new ProviderEvent(
            eventId(),
            FIRST_EVENT,
            fixture.intentId().value(),
            fixture.providerReferenceFor("challenge"),
            ProviderOutcome.REQUIRES_ACTION,
            null,
            null,
            null,
            null,
            "https://3ds.simulator.test/challenge/abc?token=SECRET-CHALLENGE-TOKEN#frag"
        ));

        String callbackPayload = jdbc.queryForObject(
            "select payload::text from provider_callbacks where merchant_id = ?",
            String.class,
            fixture.merchantId().value()
        );
        String attemptPayload = jdbc.queryForObject(
            "select response_payload::text from payment_attempts where merchant_id = ?",
            String.class,
            fixture.merchantId().value()
        );

        for (String stored : List.of(callbackPayload, attemptPayload)) {
            assertThat(stored)
                .as("the query string is a credential and must not be durable")
                .doesNotContain("SECRET-CHALLENGE-TOKEN")
                .doesNotContain("token=")
                .doesNotContain("#frag")
                .contains("https://3ds.simulator.test/challenge/abc")
                .contains("REQUIRES_ACTION");
        }
    }

    /**
     * THE TRANSACTION BOUNDARY, from the far end. The callback row, the moved attempt and the moved
     * intent are all in the session when the outbox append throws. If they are not in one transaction
     * they commit, and this test finds them.
     * <p>
     * A happy-path test cannot tell four transactions from one.
     */
    @Test
    void leavesNothingBehindWhenTheOutboxAppendFails() {
        Fixture fixture = processingIntent();

        RecordProviderCallbackService sabotaged = serviceWithOutbox(event -> {
            throw new IllegalStateException("outbox is down");
        });

        assertThatThrownBy(() -> deliver(sabotaged, succeeded(fixture, FIRST_EVENT)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox is down");

        assertThat(statusOf(fixture))
            .as("no payment may be marked collected without its event")
            .isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(callbackCount(fixture))
            .as("and the callback must not be recorded as handled")
            .isZero();
        assertThat(transitions(fixture)).containsExactly("REQUIRES_CONFIRMATION->PROCESSING");
    }

    // --- helpers ------------------------------------------------------------------------

    /** The production service with the outbox swapped out, so a late write can be made to fail. */
    private RecordProviderCallbackService serviceWithOutbox(OutboxWriter events) {
        return new RecordProviderCallbackService(
            paymentIntents, attempts, history, callbacks, events, transactionTemplate, clock
        );
    }

    private ProviderCallbackOutcome deliver(RecordProviderCallbackService service, ProviderEvent event) {
        return service.record(new RecordProviderCallbackCommand(PROVIDER, event, hashOf(event)));
    }

    private Object deliverCatching(ProviderEvent event) {
        try {
            return deliver(callbackService, event);
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private PaymentIntentStatus deliveredOutcomeOf(ProviderOutcome outcome) {
        Fixture fixture = processingIntent();

        assertThat(deliver(callbackService, new ProviderEvent(
            eventId(),
            FIRST_EVENT,
            fixture.intentId().value(),
            "sim_pay_" + UUID.randomUUID(),
            outcome,
            outcome == ProviderOutcome.AUTHORIZED ? ORDER_AMOUNT_MINOR : null,
            outcome == ProviderOutcome.SUCCEEDED ? ORDER_AMOUNT_MINOR : null,
            outcome == ProviderOutcome.FAILED ? "do_not_honour" : null,
            outcome == ProviderOutcome.FAILED ? "Issuer declined the transaction" : null,
            null
        ))).isEqualTo(ProviderCallbackOutcome.APPLIED);

        assertThat(eventTypes(fixture))
            .as("one event per applied outcome, named for it")
            .endsWith("payment." + outcome.name().toLowerCase(java.util.Locale.ROOT));

        return statusOf(fixture);
    }

    private static ProviderEvent succeeded(Fixture fixture, Instant occurredAt) {
        return new ProviderEvent(
            fixture.eventIdFor("succeeded"), occurredAt, fixture.intentId().value(),
            fixture.providerReferenceFor("succeeded"), ProviderOutcome.SUCCEEDED, null, ORDER_AMOUNT_MINOR, null, null, null
        );
    }

    private static ProviderEvent failed(Fixture fixture, Instant occurredAt) {
        return new ProviderEvent(
            fixture.eventIdFor("failed"), occurredAt, fixture.intentId().value(),
            fixture.providerReferenceFor("failed"), ProviderOutcome.FAILED, null, null, "do_not_honour", "Issuer declined", null
        );
    }

    /**
     * A distinct event id per delivery, so that a test about ORDERING is never accidentally a test
     * about deduplication. The one place a repeat is wanted -- the duplicate tests -- reuses the same
     * {@link ProviderEvent} instance instead.
     */
    private static ProviderEvent requiresAction(Fixture fixture, Instant occurredAt) {
        return new ProviderEvent(
            eventId(), occurredAt, fixture.intentId().value(), "sim_pay_" + UUID.randomUUID(),
            ProviderOutcome.REQUIRES_ACTION, null, null, null, null,
            "https://3ds.simulator.test/challenge/abc"
        );
    }

    private static String eventId() {
        return "sim_evt_" + UUID.randomUUID();
    }

    /**
     * Stands in for the signature filter's hash of the raw body. There is no raw body at this layer
     * -- these tests drive the service, as a reconciliation job would -- so the digest is taken over
     * the redacted payload instead. What the column needs is 64 hex characters that differ per
     * delivery; the filter is what proves the production value is a hash of the actual bytes.
     */
    private static String hashOf(ProviderEvent event) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(event.redactedPayload().toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other delivery");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static <T> List<T> inParallel(List<Callable<T>> calls) throws Exception {
        CyclicBarrier startTogether = new CyclicBarrier(calls.size());

        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> futures = new ArrayList<>();

            for (Callable<T> call : calls) {
                futures.add(pool.submit(() -> {
                    startTogether.await();
                    return call.call();
                }));
            }

            List<T> results = new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get());
            }

            return results;
        }
    }

    /** A merchant, an order, an intent with a method attached, confirmed: PROCESSING, attempt 1. */
    private Fixture processingIntent() {
        MerchantId merchantId = merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();

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

        return new Fixture(merchantId, intent.paymentIntentId());
    }

    private PaymentIntentStatus statusOf(Fixture fixture) {
        return getPaymentIntentService.getById(fixture.merchantId(), fixture.intentId()).status();
    }

    private List<Map<String, Object>> callbackRows(Fixture fixture) {
        return jdbc.queryForList("""
            select provider, external_event_id, merchant_id, payment_intent_id, outcome,
                   payload ->> 'capturedAmountMinor' as claimed_captured
              from provider_callbacks
             where merchant_id = ?
             order by received_at, external_event_id
            """, fixture.merchantId().value());
    }

    private Map<String, Object> onlyCallbackRow(Fixture fixture) {
        List<Map<String, Object>> rows = callbackRows(fixture);
        assertThat(rows).as("exactly one callback row").hasSize(1);
        return rows.get(0);
    }

    private Map<String, Object> onlyAttemptRow(Fixture fixture) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            select status, provider_reference, last_provider_event_at, failure_code
              from payment_attempts
             where merchant_id = ?
            """, fixture.merchantId().value());

        assertThat(rows).as("exactly one attempt").hasSize(1);
        return rows.get(0);
    }

    /** A callback row written with no Java between the insert and the constraint. */
    private int rawCallback(Fixture fixture, String externalEventId) {
        return jdbc.update("""
            INSERT INTO provider_callbacks (
                provider, external_event_id, merchant_id, payment_intent_id, payload_hash,
                payload, outcome, occurred_at, received_at, processed_at
            ) VALUES (?, ?, ?, ?, ?, '{"eventId": "raw"}'::jsonb, 'APPLIED', ?, ?, ?)
            """,
            PROVIDER,
            externalEventId,
            fixture.merchantId().value(),
            fixture.intentId().value(),
            "0".repeat(64),
            java.sql.Timestamp.from(FIRST_EVENT),
            java.sql.Timestamp.from(FIRST_EVENT),
            java.sql.Timestamp.from(FIRST_EVENT)
        );
    }

    private long callbackCount(Fixture fixture) {
        return jdbc.queryForObject(
            "select count(*) from provider_callbacks where merchant_id = ?",
            Long.class,
            fixture.merchantId().value()
        );
    }

    private long attemptCount(Fixture fixture) {
        return jdbc.queryForObject(
            "select count(*) from payment_attempts where merchant_id = ?",
            Long.class,
            fixture.merchantId().value()
        );
    }

    /**
     * The intent's transitions from the confirm onwards, as {@code FROM->TO}. Creation and attach are
     * dropped so a test about callbacks reads as one.
     */
    private List<String> transitions(Fixture fixture) {
        return jdbc.queryForList("""
            select from_status || '->' || to_status as move
              from payment_state_history
             where merchant_id = ?
               and from_status in ('REQUIRES_CONFIRMATION', 'PROCESSING', 'REQUIRES_ACTION')
             order by occurred_at, payment_state_history_id
            """, String.class, fixture.merchantId().value());
    }

    private String actorOfTheLastTransition(Fixture fixture) {
        return jdbc.queryForList("""
            select actor_type
              from payment_state_history
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

    private String capturedAmountOfTheLastEvent(Fixture fixture) {
        return jdbc.queryForList("""
            select payload ->> 'capturedAmountMinor'
              from outbox_events
             where merchant_id = ?
             order by occurred_at desc, event_id desc
             limit 1
            """, String.class, fixture.merchantId().value()).get(0);
    }

    /** One merchant's one intent, sitting in PROCESSING with one attempt open against it. */
    private record Fixture(MerchantId merchantId, PaymentIntentId intentId) {

        /** Stable per fixture and per kind, so redelivering "the same event" really is the same. */
        String eventIdFor(String kind) {
            return "sim_evt_" + kind + "_" + intentId.value();
        }

        /**
         * Unique per fixture, because uq_payment_attempts_provider_reference is PROVIDER-global
         * rather than merchant-scoped -- deliberately so, per V9. A literal shared between tests
         * collides, which is the index doing its job and not a test to work around.
         */
        String providerReferenceFor(String kind) {
            return "sim_pay_" + kind + "_" + intentId.value();
        }
    }
}
