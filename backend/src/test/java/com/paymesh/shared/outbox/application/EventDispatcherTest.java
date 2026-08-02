package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.application.Fakes.AlreadyProcessedEverything;
import com.paymesh.shared.outbox.application.Fakes.ImmediateTransactions;
import com.paymesh.shared.outbox.application.Fakes.InMemoryProcessedEvents;
import com.paymesh.shared.outbox.application.Fakes.RecordingHandler;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The dispatch contract, in plain JUnit with no Spring, no database and no relay (ADR-016).
 * <p>
 * What this class CANNOT prove is that the inbox row and the handler's writes commit together --
 * {@code ImmediateTransactions} rolls nothing back, because a list of objects has nothing to roll
 * back. That is proved in {@code EventDeliveryIntegrationTest} against PostgreSQL, which is the only
 * thing that can arbitrate it. Everything below is a rule about WHO is called and HOW OFTEN, which
 * is exactly what a plain object should be able to answer.
 */
class EventDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final InMemoryProcessedEvents inbox = new InMemoryProcessedEvents(transactions);

    // --- the happy path -----------------------------------------------------------------

    @Test
    void deliversAnEventToEveryHandlerSubscribedToItsType() {
        RecordingHandler ledger = new RecordingHandler("ledger.payment", "payment.succeeded");
        RecordingHandler order = new RecordingHandler("order.payment", "payment.succeeded");

        dispatcherOf(List.of(ledger, order)).dispatch(event("payment.succeeded", "pi_1"));

        assertThat(ledger.handled()).hasSize(1);
        assertThat(order.handled()).hasSize(1);
    }

    @Test
    void deliversNothingToAHandlerSubscribedToAnotherType() {
        RecordingHandler cancelled = new RecordingHandler("order.cancel", "payment.cancelled");

        dispatcherOf(List.of(cancelled)).dispatch(event("payment.succeeded", "pi_1"));

        assertThat(cancelled.handled()).isEmpty();
    }

    /**
     * AN EVENT NOBODY HANDLES IS NOT AN ERROR. {@code order.created} and {@code payment.created}
     * have no consumer, and the relay must stamp them published rather than count them failed --
     * "published" means "delivered to everyone subscribed", which for nobody is immediate.
     */
    @Test
    void treatsAnEventWithNoHandlerAsDoneRatherThanFailed() {
        EventDispatcher dispatcher = dispatcherOf(List.of());

        dispatcher.dispatch(event("order.created", "ord_1"));

        assertThat(transactions.executions())
            .as("no handler means no inbox row and no transaction")
            .isZero();
    }

    // --- deduplication, which is the whole reason the inbox exists -------------------------

    /**
     * DUPLICATE DELIVERY IS A NO-OP, AND THIS IS THE MOST IMPORTANT TEST IN THE CLASS. Delivery is
     * at-least-once by construction -- the {@code published_at} stamp commits separately from the
     * handler -- so the same event WILL arrive twice, and applying a payment twice is the failure
     * this entire pattern exists to prevent.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code markProcessed} guard in
     * {@code EventDispatcher.dispatch} and call the handler unconditionally. The handler then sees
     * the event twice.
     */
    @Test
    void deliversTheSameEventToOneConsumerExactlyOnce() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        EventDispatcher dispatcher = dispatcherOf(List.of(handler));
        OutboxEvent event = event("payment.succeeded", "pi_1");

        dispatcher.dispatch(event);
        dispatcher.dispatch(event);
        dispatcher.dispatch(event);

        assertThat(handler.handled()).as("three deliveries, one application").hasSize(1);
        assertThat(inbox.size()).isEqualTo(1);
    }

    /**
     * THE DEDUP IS PER CONSUMER, WHICH IS WHY {@code consumer_name} LEADS THE PRIMARY KEY. A key on
     * {@code event_id} alone would mean the first consumer to run silently starves every other one
     * -- and the symptom, days later, would be "the Ledger never posts" with nothing in any log.
     */
    @Test
    void doesNotLetOneConsumerSuppressAnotherForTheSameEvent() {
        RecordingHandler order = new RecordingHandler("order.payment", "payment.succeeded");
        RecordingHandler ledger = new RecordingHandler("ledger.payment", "payment.succeeded");
        EventDispatcher dispatcher = dispatcherOf(List.of(order, ledger));
        OutboxEvent event = event("payment.succeeded", "pi_1");

        dispatcher.dispatch(event);
        dispatcher.dispatch(event);

        assertThat(order.handled()).hasSize(1);
        assertThat(ledger.handled()).hasSize(1);
        assertThat(inbox.size()).as("one row per consumer, not one per event").isEqualTo(2);
    }

    /** Two different events are two different claims, however alike their payloads. */
    @Test
    void deliversTwoDistinctEventsSeparately() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");
        EventDispatcher dispatcher = dispatcherOf(List.of(handler));

        dispatcher.dispatch(event("payment.succeeded", "pi_1"));
        dispatcher.dispatch(event("payment.succeeded", "pi_2"));

        assertThat(handler.handledAggregateIds()).containsExactly("pi_1", "pi_2");
    }

    /** An inbox that claims nothing means every delivery is a duplicate; nothing is handled. */
    @Test
    void skipsAHandlerEntirelyWhenTheInboxSaysItAlreadyRan() {
        RecordingHandler handler = new RecordingHandler("order.payment", "payment.succeeded");

        new EventDispatcher(
            List.of(handler), new AlreadyProcessedEverything(), transactions, CLOCK
        ).dispatch(event("payment.succeeded", "pi_1"));

        assertThat(handler.handled()).isEmpty();
    }

    // --- the transaction boundary ---------------------------------------------------------

    /**
     * ONE TRANSACTION PER (HANDLER, EVENT), AND THE CLAIM IS INSIDE IT. The count is what matters:
     * two handlers means two transactions, not one, so a Ledger failure cannot roll back Order's
     * committed work.
     */
    @Test
    void opensOneTransactionPerHandlerWithTheInboxClaimInsideIt() {
        RecordingHandler order = new RecordingHandler("order.payment", "payment.succeeded");
        RecordingHandler ledger = new RecordingHandler("ledger.payment", "payment.succeeded");

        dispatcherOf(List.of(order, ledger)).dispatch(event("payment.succeeded", "pi_1"));

        assertThat(transactions.executions()).isEqualTo(2);
        assertThat(inbox.claimedInsideATransaction()).isTrue();
    }

    /**
     * A HANDLER'S FAILURE PROPAGATES, and must. Swallowing it here would tell the relay a failed
     * delivery succeeded, the event would be stamped published, and it would never be retried --
     * the one way this design loses an event permanently.
     */
    @Test
    void letsAHandlerFailurePropagateToTheRelay() {
        RecordingHandler exploding = new RecordingHandler(
            "order.payment",
            "payment.succeeded",
            event -> {
                throw new IllegalStateException("the order module is down");
            }
        );

        assertThatThrownBy(() -> dispatcherOf(List.of(exploding)).dispatch(
            event("payment.succeeded", "pi_1")
        )).isInstanceOf(IllegalStateException.class).hasMessage("the order module is down");
    }

    // --- registration -----------------------------------------------------------------------

    /**
     * Two handlers of one type calling themselves the same thing would SHARE an inbox row, so
     * whichever ran second would silently never run again. Refused at construction, where a startup
     * failure names it, rather than discovered as a consumer that quietly stopped.
     */
    @Test
    void refusesTwoHandlersOfOneTypeThatShareAConsumerName() {
        List<EventHandler> clashing = List.of(
            new RecordingHandler("order.payment", "payment.succeeded"),
            new RecordingHandler("order.payment", "payment.succeeded")
        );

        assertThatThrownBy(() -> dispatcherOf(clashing))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("order.payment");
    }

    /** The same name on two DIFFERENT types is fine: they are different rows in the inbox. */
    @Test
    void allowsOneConsumerNameAcrossTwoEventTypes() {
        List<EventHandler> handlers = List.of(
            new RecordingHandler("order.payments", "payment.succeeded"),
            new RecordingHandler("order.payments", "payment.failed")
        );

        assertThat(dispatcherOf(handlers)).isNotNull();
    }

    // --- helpers ------------------------------------------------------------------------------

    private EventDispatcher dispatcherOf(List<EventHandler> handlers) {
        return new EventDispatcher(handlers, inbox, transactions, CLOCK);
    }

    private static OutboxEvent event(String eventType, String aggregateId) {
        return new OutboxEvent(
            EventId.generate(),
            MerchantId.generate(),
            "PAYMENT_INTENT",
            aggregateId,
            eventType,
            1,
            Map.of("aggregateId", aggregateId),
            NOW
        );
    }
}
