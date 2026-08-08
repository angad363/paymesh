package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.application.Fakes.ImmediateTransactions;
import com.paymesh.shared.outbox.application.Fakes.InMemoryOutbox;
import com.paymesh.shared.outbox.application.Fakes.InMemoryProcessedEvents;
import com.paymesh.shared.outbox.application.Fakes.RecordingHandler;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService.RelayResult;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay's rules, in plain JUnit with no scheduler and no database (ADR-016).
 * <p>
 * <b>That this class exists at all is the argument for keeping the logic out of
 * {@code OutboxRelay}.</b> Every rule here -- ordering, the batch bound, what a failure isolates and
 * what it defers, when {@code published_at} is stamped -- is exercised by calling one method against
 * a fixed {@link Clock}. Had it lived inside the {@code @Scheduled} method, testing it would have
 * meant booting a context and waiting for a tick.
 */
class PublishOutboxEventsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MerchantId MERCHANT = MerchantId.generate();

    /** A real mapper, not a stub: the payload parse is now part of what these tests exercise. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryOutbox outbox = new InMemoryOutbox();
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final InMemoryProcessedEvents inbox = new InMemoryProcessedEvents(transactions);

    // --- the happy path ---------------------------------------------------------------------

    @Test
    void deliversAnUnpublishedEventAndStampsIt() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        UnpublishedEvent row = row("payment.succeeded", "pi_1", NOW.minusSeconds(10));
        outbox.append(row);

        RelayResult result = relayOf(List.of(handler), 100).publish();

        assertThat(result).isEqualTo(new RelayResult(1, 1, 0, 0, 0));
        assertThat(handler.handled()).hasSize(1);
        assertThat(outbox.published()).containsEntry(row.eventId(), NOW);
    }

    /**
     * A SECOND PASS SEES NOTHING. {@code published_at IS NULL} is the entire status model (V7), so a
     * stamped event simply falls out of the claim query -- there is no status column to disagree
     * with it and no cursor to keep.
     */
    @Test
    void doesNotDeliverAnEventItHasAlreadyPublished() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(10)));

        PublishOutboxEventsService relay = relayOf(List.of(handler), 100);

        assertThat(relay.publish().published()).isEqualTo(1);
        assertThat(relay.publish()).isEqualTo(new RelayResult(0, 0, 0, 0, 0));
        assertThat(handler.handled()).hasSize(1);
    }

    /** An idle platform costs one query and opens no transaction at all. */
    @Test
    void doesNothingWhenTheBacklogIsEmpty() {
        assertThat(relayOf(List.of(), 100).publish()).isEqualTo(new RelayResult(0, 0, 0, 0, 0));
        assertThat(transactions.executions()).isZero();
    }

    /** An event nobody subscribes to is published, not failed. See {@code EventDispatcherTest}. */
    @Test
    void publishesAnEventThatNoConsumerWants() {
        UnpublishedEvent row = row("order.created", "ord_1", NOW.minusSeconds(10));
        outbox.append(row);

        assertThat(relayOf(List.of(), 100).publish()).isEqualTo(new RelayResult(1, 1, 0, 0, 0));
        assertThat(outbox.isPublished(row.eventId())).isTrue();
    }

    // --- ORDERING -------------------------------------------------------------------------------

    /**
     * TWO EVENTS FOR ONE AGGREGATE ARRIVE IN THE ORDER THEY HAPPENED. A consumer that saw a
     * payment's outcome before the payment would have to reconstruct the sequence from timestamps,
     * which is exactly the burden the relay exists to carry.
     * <p>
     * <b>Sabotage that must turn this red:</b> order the claim query by {@code event_id} (or by
     * insertion) instead of {@code occurred_at}. The ids are random UUIDs, so the delivered order
     * stops matching the causal one.
     */
    @Test
    void deliversTwoEventsForOneAggregateInOccurredAtOrder() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");

        // Appended NEWEST FIRST on purpose: a relay that returned insertion order would pass a test
        // whose fixture happened to be in the right order already.
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(10), "later"));
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(30), "earlier"));
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(20), "middle"));

        relayOf(List.of(handler), 100).publish();

        assertThat(handler.handled().stream().map(event -> event.payload().get("marker")))
            .containsExactly("earlier", "middle", "later");
    }

    /** The order holds across aggregates too -- the batch is one sequence, not one per aggregate. */
    @Test
    void deliversAcrossAggregatesInOccurredAtOrder() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        outbox.append(row("payment.succeeded", "pi_2", NOW.minusSeconds(10)));
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(30)));

        relayOf(List.of(handler), 100).publish();

        assertThat(handler.handledAggregateIds()).containsExactly("pi_1", "pi_2");
    }

    // --- POISON ISOLATION -----------------------------------------------------------------------

    /**
     * ONE UNMAPPABLE ROW MUST NOT WEDGE THE RELAY, AND THIS IS THE TEST OPEN ITEM 2 IS ABOUT.
     * <p>
     * Both existing sweeps map every candidate through the aggregate INSIDE the repository call --
     * outside the per-item try/catch -- so one bad row throws out of the whole pass, and because the
     * ordering is oldest-first it sits at the head of every subsequent batch and disables the job
     * permanently and silently. This relay must not reproduce it.
     * <p>
     * The poisoned row here carries a blank {@code eventId}, which {@code EventId.from} refuses. It
     * is the OLDEST row, so it is claimed first and would take everything behind it down with it.
     * <p>
     * <b>Sabotage that must turn this red:</b> move {@code row.toEvent()} above the {@code try} in
     * {@code PublishOutboxEventsService.publish}. The whole pass then throws and the healthy event is
     * never delivered.
     */
    @Test
    void keepsPublishingAfterOneRowCannotBeMapped() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        outbox.append(new UnpublishedEvent(
            "", MERCHANT.value(), "PAYMENT_INTENT", "pi_corrupt", "payment.succeeded", 1,
            "{}", NOW.minusSeconds(60), 0
        ));
        UnpublishedEvent healthy = row("payment.succeeded", "pi_healthy", NOW.minusSeconds(10));
        outbox.append(healthy);

        RelayResult result = relayOf(List.of(handler), 100).publish();

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(handler.handledAggregateIds()).containsExactly("pi_healthy");
        assertThat(outbox.isPublished(healthy.eventId())).isTrue();
    }

    /** The same isolation when it is a HANDLER that throws rather than the row that will not map. */
    @Test
    void keepsPublishingAfterOneHandlerFails() {
        RecordingHandler handler = new RecordingHandler(
            "order.payment",
            "payment.succeeded",
            event -> {
                if (event.aggregateId().equals("pi_poison")) {
                    throw new IllegalStateException("the order module is down for this one");
                }
            }
        );

        UnpublishedEvent poison = row("payment.succeeded", "pi_poison", NOW.minusSeconds(60));
        UnpublishedEvent healthy = row("payment.succeeded", "pi_healthy", NOW.minusSeconds(10));
        outbox.append(poison);
        outbox.append(healthy);

        RelayResult result = relayOf(List.of(handler), 100).publish();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(outbox.isPublished(healthy.eventId())).isTrue();
        assertThat(outbox.isPublished(poison.eventId()))
            .as("a failed delivery must stay unpublished, or it is never retried")
            .isFalse();
    }

    /**
     * THE ORDERING GUARANTEE SURVIVES A FAILURE, and this is the four lines that make it true. A
     * relay that merely "carried on" would deliver an aggregate's SECOND event after its first one
     * failed -- delivering a payment's outcome before the payment, which is worse than delivering
     * neither.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop the {@code poisonedAggregates} set. The later
     * event of {@code pi_poison} is then delivered out of order.
     */
    @Test
    void defersLaterEventsOfAnAggregateWhoseEarlierEventFailed() {
        RecordingHandler handler = new RecordingHandler(
            "order.payment",
            "payment.succeeded",
            event -> {
                if ("first".equals(event.payload().get("marker"))) {
                    throw new IllegalStateException("this one always fails");
                }
            }
        );

        outbox.append(row("payment.succeeded", "pi_poison", NOW.minusSeconds(60), "first"));
        outbox.append(row("payment.succeeded", "pi_poison", NOW.minusSeconds(50), "second"));
        outbox.append(row("payment.succeeded", "pi_other", NOW.minusSeconds(40), "unrelated"));

        RelayResult result = relayOf(List.of(handler), 100).publish();

        assertThat(result).isEqualTo(new RelayResult(3, 1, 1, 1, 0));
        assertThat(handler.handled().stream().map(event -> event.payload().get("marker")))
            .as("the unrelated aggregate drains; the poisoned one waits for its own head")
            .containsExactly("unrelated");
    }

    // --- batching -------------------------------------------------------------------------------

    /**
     * The batch is a bound on one pass, not on the work. Three events and a batch of two leaves the
     * third for the next pass, which is what stops a backlog loading the whole log into memory.
     */
    @Test
    void takesAtMostOneBatchPerPass() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        outbox.append(row("payment.succeeded", "pi_1", NOW.minusSeconds(30)));
        outbox.append(row("payment.succeeded", "pi_2", NOW.minusSeconds(20)));
        outbox.append(row("payment.succeeded", "pi_3", NOW.minusSeconds(10)));

        PublishOutboxEventsService batched = relayOf(List.of(handler), 2);

        assertThat(batched.publish().published()).isEqualTo(2);
        assertThat(batched.publish().published()).isEqualTo(1);
        assertThat(batched.publish().published()).isZero();
        assertThat(handler.handledAggregateIds())
            .as("a bounded batch must not change the delivery order")
            .containsExactly("pi_1", "pi_2", "pi_3");
    }

    @Test
    void refusesABatchSizeBelowOne() {
        assertThatThrownBy(() -> relayOf(List.of(), 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- THE RETRY BUDGET (ADR-025) --------------------------------------------------------------

    /**
     * A failure is now WRITTEN DOWN. Before V21 every pass started from zero knowledge, so "this has
     * failed once" and "this has failed nine hundred times" were the same row.
     */
    @Test
    void countsAFailedAttemptAndKeepsTheError() {
        UnpublishedEvent poison = row("payment.succeeded", "pi_poison", NOW.minusSeconds(60));
        outbox.append(poison);

        relayOf(List.of(alwaysFailingHandler()), 100, 10).publish();

        assertThat(outbox.attemptsFor(poison.eventId())).isEqualTo(1);
        assertThat(outbox.lastErrorFor(poison.eventId())).contains("the consumer is broken");
        assertThat(outbox.isDeadLettered(poison.eventId()))
            .as("one failure out of a budget of ten is not a reason to give up")
            .isFalse();
    }

    /**
     * THE BUDGET ACTUALLY RUNS OUT, and the pass that exhausts it says so.
     * <p>
     * <b>Sabotage that must turn this red:</b> change the relay's exhaustion test from
     * {@code attemptCount + 1 >= maxAttempts} to {@code attemptCount >= maxAttempts}. The event then
     * survives one attempt longer than the budget allows and the third pass reports nothing.
     */
    @Test
    void givesUpOnAnEventOnceTheBudgetIsSpent() {
        UnpublishedEvent poison = row("payment.succeeded", "pi_poison", NOW.minusSeconds(60));
        outbox.append(poison);

        PublishOutboxEventsService relay = relayOf(List.of(alwaysFailingHandler()), 100, 3);

        assertThat(relay.publish().deadLettered()).isZero();
        assertThat(relay.publish().deadLettered()).isZero();
        assertThat(relay.publish().deadLettered())
            .as("the third failure reaches the budget of three")
            .isEqualTo(1);

        assertThat(outbox.isDeadLettered(poison.eventId())).isTrue();
        assertThat(outbox.isPublished(poison.eventId()))
            .as("giving up must not look like delivering")
            .isFalse();
        assertThat(relay.publish())
            .as("a dead-lettered row leaves the claim query entirely")
            .isEqualTo(new RelayResult(0, 0, 0, 0, 0));
    }

    /**
     * THE WHOLE POINT OF THE BUDGET, AND THE CLOSE OF OPEN ITEM 14. A permanently failing event used
     * to freeze its aggregate's later events FOREVER -- retried at the head of every pass, its
     * successors deferred behind it every pass. Here the successor is delivered once the relay has
     * given up on its predecessor.
     * <p>
     * Note what the assertion protects on the way there: the successor stays undelivered while the
     * budget is still being spent. Ordering is preserved right up to the moment the relay abandons
     * the head, and only then does the aggregate drain.
     * <p>
     * <b>Sabotage that must turn this red:</b> drop {@code and dead_lettered_at is null} from the
     * claim query. The poisoned head is re-claimed on every pass and re-poisons the aggregate, so
     * "second" is never delivered.
     */
    @Test
    void unblocksAnAggregateOnceItsPoisonedHeadIsAbandoned() {
        RecordingHandler handler = new RecordingHandler(
            "order.payment",
            "payment.succeeded",
            event -> {
                if ("first".equals(event.payload().get("marker"))) {
                    throw new IllegalStateException("this one always fails");
                }
            }
        );

        outbox.append(row("payment.succeeded", "pi_stuck", NOW.minusSeconds(60), "first"));
        outbox.append(row("payment.succeeded", "pi_stuck", NOW.minusSeconds(50), "second"));

        PublishOutboxEventsService relay = relayOf(List.of(handler), 100, 2);

        assertThat(relay.publish()).isEqualTo(new RelayResult(2, 0, 1, 1, 0));
        assertThat(handler.handled())
            .as("while the head is still being retried, its successor must wait")
            .isEmpty();

        assertThat(relay.publish().deadLettered()).isEqualTo(1);

        assertThat(relay.publish().published())
            .as("the head is abandoned, so the aggregate finally drains")
            .isEqualTo(1);
        assertThat(handler.handled().stream().map(event -> event.payload().get("marker")))
            .containsExactly("second");
    }

    /**
     * A row that can never be mapped is exactly the row that can never succeed on retry, so it must
     * be dead-letterable. This is why the port takes a raw {@code String} event id rather than a
     * validated {@code EventId} -- demanding one would make the permanently-broken case the one case
     * that blocks its aggregate forever.
     */
    @Test
    void givesUpOnARowThatCanNeverBeMapped() {
        UnpublishedEvent corrupt = new UnpublishedEvent(
            EventId.generate().value(), "not-a-merchant-id", "PAYMENT_INTENT", "pi_corrupt",
            "payment.succeeded", 1, "{}", NOW.minusSeconds(60), 0
        );
        outbox.append(corrupt);

        assertThat(relayOf(List.of(), 100, 1).publish().deadLettered()).isEqualTo(1);
        assertThat(outbox.isDeadLettered(corrupt.eventId())).isTrue();
    }

    /**
     * THE BOOKKEEPING MUST NOT BE ABLE TO KILL THE SWEEP. The likeliest reason recording an attempt
     * fails is that the database is down -- which is also the likeliest reason the delivery just
     * failed. If that rethrew, one unreachable database would abort the pass over every other
     * aggregate: the precise one-bad-row-kills-the-job shape open item 2 describes.
     */
    @Test
    void keepsSweepingWhenRecordingTheAttemptItselfFails() {
        outbox.failAttemptRecordingWith(new IllegalStateException("the database is gone"));
        outbox.append(row("payment.succeeded", "pi_poison", NOW.minusSeconds(60)));

        RelayResult result = relayOf(List.of(alwaysFailingHandler()), 100, 1).publish();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.deadLettered())
            .as("the attempt did not stick, so the budget did not move and nothing was abandoned")
            .isZero();
    }

    /** A budget of zero would abandon every event on its first transient failure. */
    @Test
    void refusesAMaxAttemptsBelowOne() {
        assertThatThrownBy(() -> relayOf(List.of(), 100, 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * A retry budget high enough that no test using this overload can trip it by accident. The
     * dead-letter tests below ask for a small one explicitly, so a budget appearing in a test is
     * always the thing that test is about.
     */
    private PublishOutboxEventsService relayOf(List<EventHandler> handlers, int batchSize) {
        return relayOf(handlers, batchSize, 1000);
    }

    private PublishOutboxEventsService relayOf(
        List<EventHandler> handlers, int batchSize, int maxAttempts
    ) {
        return new PublishOutboxEventsService(
            outbox,
            new EventDispatcher(handlers, inbox, transactions, CLOCK),
            transactions,
            JSON,
            CLOCK,
            batchSize,
            maxAttempts
        );
    }

    /** Fails every delivery, so a test can drive the budget to exhaustion without contriving data. */
    private static RecordingHandler alwaysFailingHandler() {
        return new RecordingHandler("order.payment", "payment.succeeded", event -> {
            throw new IllegalStateException("the consumer is broken");
        });
    }

    private static UnpublishedEvent row(String eventType, String aggregateId, Instant occurredAt) {
        return row(eventType, aggregateId, occurredAt, null);
    }

    private static UnpublishedEvent row(
        String eventType,
        String aggregateId,
        Instant occurredAt,
        String marker
    ) {
        return new UnpublishedEvent(
            EventId.generate().value(),
            MERCHANT.value(),
            "PAYMENT_INTENT",
            aggregateId,
            eventType,
            1,
            marker == null ? "{}" : "{\"marker\":\"" + marker + "\"}",
            occurredAt,
            0
        );
    }
}
