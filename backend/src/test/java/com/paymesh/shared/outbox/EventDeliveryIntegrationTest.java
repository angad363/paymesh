package com.paymesh.shared.outbox;

import com.paymesh.TestcontainersConfiguration;
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
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.shared.outbox.application.EventDispatcher;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.application.ProcessedEventRepository;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService;
import com.paymesh.shared.outbox.application.PublishOutboxEventsService.RelayResult;
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE POINT OF THE WHOLE EVENT-DELIVERY PR, against a real PostgreSQL (ADR-016).
 * <p>
 * The loop this proves is the one the walkthrough used to call "the mailbox with no postman": a
 * payment succeeds, {@code payment.succeeded} lands in the outbox in that transaction, the relay
 * reads it, Order's consumer applies it, and {@code orders.status} finally reaches PAID. Every step
 * of that existed before this branch except the two in the middle.
 * <p>
 * Deliberately NOT {@code @Transactional}. A test transaction would wrap the delivery and the state
 * change in one outer transaction that rolls back at the end, and every assertion below would then
 * pass whether or not the dispatcher opened a transaction of its own -- which is precisely the
 * failure this class exists to make impossible. The commits here are real, so each test registers
 * its own merchant and scopes its queries to it.
 * <p>
 * The relay is a plain bean under the {@code dev} profile the suite runs on, so these tests call
 * {@code publish()} directly and never wait for a scheduler. The timer is off in
 * {@code application-dev.yaml} for exactly that reason.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class EventDeliveryIntegrationTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-02T11:00:00Z");
    private static final Instant RELAY_RAN_AT = Instant.parse("2026-08-02T11:00:05Z");
    private static final long ORDER_AMOUNT_MINOR = 4000;
    private static final String PROVIDER = "SIMULATOR";

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private TransactionTemplate transactionTemplate;

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
    private JdbcClient jdbc;

    // --- THE LOOP -----------------------------------------------------------------------------

    /**
     * THE HEADLINE, THROUGH THE AUTOMATIC PATH: order → intent → attach → confirm → provider says
     * SUCCEEDED → relay → the order reads PAID.
     * <p>
     * Nothing here calls Order. Payment writes an event, and Order moves its own column because it
     * read one -- which is why {@code ModuleBoundaryTest.orderNeverImportsPayment} still has an
     * empty allowlist.
     */
    @Test
    void movesAnOrderToPaidWhenTheProviderReportsAFullCollection() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);

        assertThat(callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR)))
            .isEqualTo(ProviderCallbackOutcome.APPLIED);

        assertThat(status(fixture))
            .as("nothing has been delivered yet, so the order is still PENDING")
            .isEqualTo(OrderStatus.PENDING);

        drain();

        assertThat(status(fixture)).isEqualTo(OrderStatus.PAID);
        assertThat(amountPaid(fixture)).isEqualTo(ORDER_AMOUNT_MINOR);
    }

    /**
     * THE PARTIAL CASE, THROUGH MANUAL CAPTURE, AND IT IS THE ONE WORTH BREAKING THINGS OVER.
     * <p>
     * The merchant collects 3000 of an authorized 4000. The order owes 4000, so it is PARTIALLY_PAID
     * and not PAID -- and this is the path that makes {@code OrderStatus.PARTIALLY_PAID} reachable
     * for the first time since V5 declared it.
     * <p>
     * <b>Sabotage that must turn this red:</b> in {@code Order.markPaid}, compare the captured figure
     * against anything but the order's own amount -- the payload's {@code amountMinor}, say. On the
     * capture path that number equals the capture, so the order comes out PAID and 1000 minor units
     * of outstanding balance vanish.
     */
    @Test
    void movesAnOrderToPartiallyPaidWhenOnlyPartOfItWasCollected() {
        Fixture fixture = authorizedIntent();

        capturePaymentIntentService.capture(fixture.merchantId(), fixture.intentId(), 3000L);

        drain();

        assertThat(status(fixture)).isEqualTo(OrderStatus.PARTIALLY_PAID);
        assertThat(amountPaid(fixture)).isEqualTo(3000L);
    }

    /**
     * BOTH EMITTERS PRODUCE ONE SHAPE, WHICH IS THE CONTRACT BUG ADR-016 SECTION 6 FIXES. Before it,
     * the capture path carried {@code customerId}, {@code captureMethod} and {@code capturedAt} while
     * the provider path carried {@code occurredAt} and none of the other three -- same event name,
     * same version 1, two shapes.
     * <p>
     * Asserted on the stored JSON rather than on a Java object, because the disagreement was in what
     * a consumer would read out of the column.
     */
    @Test
    void emitsOnePaymentSucceededShapeFromBothTheProviderAndTheCapturePath() {
        Fixture automatic = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(automatic, ORDER_AMOUNT_MINOR));

        Fixture manual = authorizedIntent();
        capturePaymentIntentService.capture(manual.merchantId(), manual.intentId(), null);

        for (Fixture fixture : List.of(automatic, manual)) {
            String payload = succeededPayload(fixture);

            assertThat(payload)
                .as("one shape, whichever authority decided it: %s", fixture.merchantId().value())
                .contains("\"paymentIntentId\"")
                .contains("\"merchantId\"")
                .contains("\"orderId\"")
                .contains("\"customerId\"")
                .contains("\"amountMinor\"")
                .contains("\"capturedAmountMinor\"")
                .contains("\"currency\"")
                .contains("\"captureMethod\"")
                .contains("\"previousStatus\"")
                .contains("\"status\"")
                .contains("\"occurredAt\"");

            assertThat(payload)
                .as("capturedAt is gone; occurredAt carries its value")
                .doesNotContain("capturedAt");
        }
    }

    // --- IDEMPOTENCY --------------------------------------------------------------------------

    /**
     * THE INBOX ON ITS OWN, AGAINST THE REAL PRIMARY KEY, AND THIS IS THE ONLY TEST HERE THAT
     * ISOLATES IT.
     * <p>
     * The order-level test below cannot: THREE independent mechanisms stop a redelivered payment
     * being applied twice, and each one suffices alone, so removing any single one leaves it green.
     * That was measured, not assumed -- removing the inbox guard AND the service's PENDING re-check
     * still left it passing, because {@code Order.markPaid} refuses a non-PENDING order and the relay
     * counts the throw as a failure. Defense in depth is correct and is exactly what
     * {@code project-status.md} records about the idempotency filter: a partial sabotage that stays
     * green means the sabotage was unfaithful, not that the code is safe.
     * <p>
     * So this test uses a handler with no guard of its own, on an event no capability consumes. What
     * it counts is invocations, and the ONLY thing standing between one and two is
     * {@code pk_processed_events}.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code markProcessed} guard in
     * {@code EventDispatcher.dispatch}.
     */
    @Test
    void callsOneConsumerOnceHoweverManyTimesAnEventIsDispatched() {
        MerchantId merchantId = existingMerchant();
        OutboxEvent event = plainEvent(merchantId, "test.only");
        CountingHandler handler = new CountingHandler("test.counting-consumer", "test.only");

        EventDispatcher dispatcher = new EventDispatcher(
            List.of(handler), processedEvents, transactionTemplate,
            Clock.fixed(RELAY_RAN_AT, ZoneOffset.UTC)
        );

        dispatcher.dispatch(event);
        dispatcher.dispatch(event);
        dispatcher.dispatch(event);

        assertThat(handler.calls())
            .as("three deliveries, one application; the primary key is the only thing deciding that")
            .isEqualTo(1);
        assertThat(inboxRowsFor(event.eventId())).isEqualTo(1);
    }

    /**
     * THE SAME EVENT REDELIVERED LEAVES THE ORDER WITH ONE TRANSITION, ONE TIMELINE ROW AND ONE
     * ANNOUNCEMENT.
     * <p>
     * The event is un-stamped between the passes, which is exactly what a crash between the handler's
     * commit and the {@code published_at} stamp leaves behind -- the ordinary at-least-once case, not
     * an exotic one.
     * <p>
     * <b>This is a whole-path assertion, NOT a test of the inbox</b>, and the difference is worth
     * being precise about. Three mechanisms independently prevent the double-apply -- the inbox row,
     * the service's PENDING re-check, and {@code Order.markPaid}'s own refusal -- and removing any
     * one of them leaves this green. The inbox is isolated by the test above; this one proves the
     * three compose into the right end state, which no single-mechanism test can say.
     */
    @Test
    void appliesAPaymentExactlyOnceEvenWhenTheEventIsDeliveredTwice() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        drain();
        unpublish(fixture);
        RelayResult second = drain();

        assertThat(second.published())
            .as("the relay re-reads it and re-delivers it")
            .isGreaterThanOrEqualTo(1);
        assertThat(status(fixture)).isEqualTo(OrderStatus.PAID);
        assertThat(orderStateHistoryRows(fixture, "PAID"))
            .as("one transition, one timeline row")
            .isEqualTo(1);
        assertThat(eventsOfType(fixture, "order.paid"))
            .as("one transition, one announcement")
            .isEqualTo(1);
        assertThat(inboxRows(fixture)).isEqualTo(1);
    }

    /**
     * THE INBOX ROW AND THE STATE CHANGE COMMIT TOGETHER, and this is the only test that can prove
     * it -- a plain JUnit double rolls nothing back.
     * <p>
     * A handler that throws AFTER the claim must take the claim with it, or the event is marked
     * consumed while nothing was consumed and it is never redelivered: the work is lost permanently,
     * with no error anywhere.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code transactions.execute} wrap in
     * {@code EventDispatcher.dispatch} and call the repository and the handler directly. The inbox
     * row then survives the failure and this finds it.
     */
    @Test
    void rollsBackTheInboxRowWhenTheHandlerFails() {
        MerchantId merchantId = existingMerchant();
        OutboxEvent event = plainEvent(merchantId, "test.only");

        EventDispatcher dispatcher = new EventDispatcher(
            List.of(new ExplodingHandler("test.consumer", "test.only")),
            processedEvents,
            transactionTemplate,
            Clock.fixed(RELAY_RAN_AT, ZoneOffset.UTC)
        );

        assertThat(catchFailure(() -> dispatcher.dispatch(event)))
            .as("the failure must reach the relay, not be swallowed")
            .isNotNull();

        assertThat(inboxRowsFor(event.eventId()))
            .as("a claim that authorized nothing must not survive")
            .isZero();
    }

    /** The converse: a handler that succeeds leaves exactly one claim behind. */
    @Test
    void keepsTheInboxRowWhenTheHandlerSucceeds() {
        MerchantId merchantId = existingMerchant();
        OutboxEvent event = plainEvent(merchantId, "test.only");

        new EventDispatcher(
            List.of(new NoOpHandler("test.consumer", "test.only")),
            processedEvents,
            transactionTemplate,
            Clock.fixed(RELAY_RAN_AT, ZoneOffset.UTC)
        ).dispatch(event);

        assertThat(inboxRowsFor(event.eventId())).isEqualTo(1);
    }

    // --- THE RELAY ITSELF ----------------------------------------------------------------------

    /**
     * ONE UNMAPPABLE ROW MUST NOT WEDGE THE RELAY, against the real table this time.
     * <p>
     * The poisoned row is written by raw JDBC with an {@code event_version} of 0 -- the domain and
     * {@code ck_outbox_events_version} both refuse it, so nothing in the application can produce it,
     * which is why the insert bypasses the application entirely. It is the OLDEST unpublished row, so
     * it sits at the head of the batch and would take everything behind it down with it.
     * <p>
     * <b>Sabotage that must turn this red:</b> move {@code row.toEvent()} above the {@code try} in
     * {@code PublishOutboxEventsService.publish} -- the shape open item 2 describes in both existing
     * sweeps. The whole pass then throws and the healthy order never reaches PAID.
     */
    @Test
    void drainsTheRestOfTheBatchWhenOneRowCannotBeMapped() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        insertUnmappableEvent(fixture.merchantId(), CREATED_AT.minusSeconds(3600));

        RelayResult result = drain();

        assertThat(result.failed()).isGreaterThanOrEqualTo(1);
        assertThat(status(fixture))
            .as("the healthy event behind the poison row must still be delivered")
            .isEqualTo(OrderStatus.PAID);
    }

    /**
     * A DELIVERED EVENT IS STAMPED AND NEVER SEEN AGAIN. {@code published_at IS NULL} is the entire
     * status model (V7): there is no status column to disagree with it and no cursor to keep.
     */
    @Test
    void stampsPublishedAtSoASecondPassSeesNothing() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        // Drained rather than one pass, and it takes MORE THAN ONE. The first delivers
        // payment.succeeded, whose consumer appends order.paid INSIDE its own transaction -- so a
        // fresh unpublished row exists by the time that pass ends. A relay that drained itself in one
        // pass would be one that consumed its own output mid-batch, which is a worse design.
        drain();

        assertThat(unpublishedCount(fixture.merchantId()))
            .as("everything this merchant produced has been delivered")
            .isZero();
    }

    /**
     * THE ORDER'S OWN {@code order.paid} IS WRITTEN IN THE CONSUMER'S TRANSACTION AND THEN
     * DELIVERED, which is what makes the loop composable: the Ledger will subscribe to a payment
     * event and a reporting read model to this one, and neither needs a second mechanism.
     */
    @Test
    void announcesOrderPaidFromInsideTheConsumerAndPublishesItToo() {
        Fixture fixture = confirmedIntent(CaptureMethod.AUTOMATIC);
        callbacks.record(succeeded(fixture, ORDER_AMOUNT_MINOR));

        drain();

        assertThat(eventsOfType(fixture, "order.paid")).isEqualTo(1);

        // The consumer's own event was appended after the relay had already claimed its batch, so a
        // later pass is what delivers it. Nothing subscribes to it, so "delivered" means stamped --
        // which is the correct handling of an event nobody wants, not a special case.
        assertThat(unpublishedCount(fixture.merchantId())).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------------

    private record Fixture(MerchantId merchantId, OrderId orderId, PaymentIntentId intentId) {
    }

    /**
     * Passes until nothing more moves. NOT a convenience -- it is what makes these tests honest in a
     * shared database.
     * <p>
     * The suite runs every {@code @SpringBootTest} against ONE container, so by the time this class
     * runs there is a backlog from every other integration test that ever created an order or an
     * intent. The batch is bounded at 100 and ordered oldest-first, so a single pass can be entirely
     * consumed by other tests' events and never reach this one's. A test calling {@code publish()}
     * once passes in isolation and fails in the suite -- which is exactly what happened before this
     * existed, and is the kind of flake that gets blamed on someone else's change.
     * <p>
     * It also reflects a real property rather than papering over one: the consumer appends
     * {@code order.paid} inside its own transaction, AFTER the pass claimed its batch, so a second
     * pass is genuinely required to deliver it. Draining is what a relay on a timer does anyway.
     * <p>
     * The loop stops when a pass PUBLISHES nothing, not when it examines nothing. A permanently
     * failing row is examined on every pass forever, and
     * {@link #drainsTheRestOfTheBatchWhenOneRowCannotBeMapped} deliberately leaves one behind.
     */
    private RelayResult drain() {
        RelayResult pass = relay.publish();
        int examined = pass.examined();
        int published = pass.published();
        int failed = pass.failed();
        int deferred = pass.deferred();

        while (pass.published() > 0) {
            pass = relay.publish();
            examined += pass.examined();
            published += pass.published();
            failed += pass.failed();
            deferred += pass.deferred();
        }

        return new RelayResult(examined, published, failed, deferred);
    }

    private Fixture confirmedIntent(CaptureMethod captureMethod) {
        MerchantId merchantId = existingMerchant();
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            ORDER_AMOUNT_MINOR, "INR", null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, ORDER_AMOUNT_MINOR, "INR", captureMethod,
            null, Map.of()
        ));

        attachPaymentMethodService.attach(
            merchantId, intent.paymentIntentId(), PaymentMethodType.CARD
        );
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        return new Fixture(merchantId, order.orderId(), intent.paymentIntentId());
    }

    /** Confirmed, then authorized by the provider, so a MANUAL capture has something to collect. */
    private Fixture authorizedIntent() {
        Fixture fixture = confirmedIntent(CaptureMethod.MANUAL);

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

    /**
     * A distinct 64-hex value. The callback layer validates the width -- it is a SHA-256 of the raw
     * body in production -- and nothing here depends on it hashing anything, only on two deliveries
     * of two different events not colliding.
     */
    private static String payloadHash() {
        return (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
    }

    private OrderStatus status(Fixture fixture) {
        return orders.findByOrderId(fixture.merchantId(), fixture.orderId()).orElseThrow().status();
    }

    private long amountPaid(Fixture fixture) {
        return orders.findByOrderId(fixture.merchantId(), fixture.orderId())
            .orElseThrow()
            .amountPaidMinor();
    }

    private String succeededPayload(Fixture fixture) {
        return jdbc.sql("""
                select payload::text from outbox_events
                 where merchant_id = ? and event_type = 'payment.succeeded'
                """)
            .param(fixture.merchantId().value())
            .query(String.class)
            .single();
    }

    private long eventsOfType(Fixture fixture, String eventType) {
        return jdbc.sql("select count(*) from outbox_events where merchant_id = ? and event_type = ?")
            .params(fixture.merchantId().value(), eventType)
            .query(Long.class)
            .single();
    }

    private long orderStateHistoryRows(Fixture fixture, String toStatus) {
        return jdbc.sql("""
                select count(*) from order_state_history
                 where merchant_id = ? and order_id = ? and to_status = ?
                """)
            .params(fixture.merchantId().value(), fixture.orderId().value(), toStatus)
            .query(Long.class)
            .single();
    }

    private long inboxRows(Fixture fixture) {
        return jdbc.sql("""
                select count(*) from processed_events p
                 where p.consumer_name = 'order.payment-succeeded'
                   and exists (select 1 from outbox_events o
                                where o.event_id = p.event_id and o.merchant_id = ?)
                """)
            .param(fixture.merchantId().value())
            .query(Long.class)
            .single();
    }

    private long inboxRowsFor(EventId eventId) {
        return jdbc.sql("select count(*) from processed_events where event_id = ?")
            .param(eventId.value())
            .query(Long.class)
            .single();
    }

    private long unpublishedCount(MerchantId merchantId) {
        return jdbc
            .sql("select count(*) from outbox_events where merchant_id = ? and published_at is null")
            .param(merchantId.value())
            .query(Long.class)
            .single();
    }

    /** Puts the merchant's events back in the backlog, which is what a crash before the stamp leaves. */
    private void unpublish(Fixture fixture) {
        jdbc.sql("update outbox_events set published_at = null where merchant_id = ?")
            .param(fixture.merchantId().value())
            .update();
    }

    /**
     * Raw JDBC, because no application path can produce this row: {@code OutboxEvent} refuses a
     * version below 1 and so does {@code ck_outbox_events_version}. The version column is therefore
     * left legal and the EVENT ID is what will not parse -- the same class of corruption, reachable
     * without disabling a constraint.
     */
    private void insertUnmappableEvent(MerchantId merchantId, Instant occurredAt) {
        jdbc.sql("""
                insert into outbox_events (event_id, merchant_id, aggregate_type, aggregate_id,
                                           event_type, event_version, payload, occurred_at)
                values (?, ?, 'PAYMENT_INTENT', 'pi_corrupt', 'payment.succeeded', 1,
                        cast('{}' as jsonb), ?)
                """)
            .params(
                "not-an-event-id",
                merchantId.value(),
                // OffsetDateTime, not Instant: the JDBC driver cannot infer a SQL type for the
                // latter. Hibernate can, which is why the application's own writes do not need this.
                occurredAt.atOffset(ZoneOffset.UTC)
            )
            .update();
    }

    private OutboxEvent plainEvent(MerchantId merchantId, String eventType) {
        OutboxEvent event = new OutboxEvent(
            EventId.generate(), merchantId, "ORDER", OrderId.generate().value(), eventType, 1,
            Map.of("marker", "inbox-transaction-test"), CREATED_AT
        );

        transactionTemplate.execute(status -> {
            outboxWriter.append(event);
            return null;
        });

        return event;
    }

    private static RuntimeException catchFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        )).merchantId();
    }

    /** Claims the event, then fails. The claim must not outlive the failure. */
    private record ExplodingHandler(String consumerName, String eventType) implements EventHandler {

        @Override
        public void handle(OutboxEvent event) {
            throw new IllegalStateException("this consumer is broken");
        }
    }

    private record NoOpHandler(String consumerName, String eventType) implements EventHandler {

        @Override
        public void handle(OutboxEvent event) {
        }
    }

    /**
     * A handler with NO guard of its own, which is the point: every other consumer in this codebase
     * re-checks the state it is about to change, so counting invocations here is the only way to
     * watch the inbox work rather than watch something downstream absorb the duplicate.
     */
    private static final class CountingHandler implements EventHandler {

        private final String consumerName;
        private final String eventType;
        private int calls;

        private CountingHandler(String consumerName, String eventType) {
            this.consumerName = consumerName;
            this.eventType = eventType;
        }

        int calls() {
            return calls;
        }

        @Override
        public String consumerName() {
            return consumerName;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(OutboxEvent event) {
            calls++;
        }
    }
}
