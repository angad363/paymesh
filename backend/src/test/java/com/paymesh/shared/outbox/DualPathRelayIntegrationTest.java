package com.paymesh.shared.outbox;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.payment.application.AttachPaymentMethodService;
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
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.outbox.infrastructure.kafka.EventEnvelope;
import com.paymesh.shared.outbox.infrastructure.kafka.KafkaEventPublisher;
import com.paymesh.shared.tenant.MerchantId;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE POINT OF THE DUAL-PATH PR (ADR-037), against a real broker and a real PostgreSQL.
 *
 * <h2>The one test in the suite that runs {@code both} mode, and it starts its own broker</h2>
 *
 * Every other {@code @SpringBootTest} runs {@code dev} → {@code in-process} → the pre-ADR-036
 * behaviour, no broker. This class overrides the mode to {@code both} with {@code @TestPropertySource}
 * (which outranks the profile yaml) and declares its own {@code KafkaContainer}, exactly as
 * {@code KafkaEventRoundTripTest} does — so the dual path is exercised without making the other ~450
 * tests pay for a Kafka. In {@code both} mode the {@code KafkaEventListener} bean is registered and
 * the relay's second sink is live.
 *
 * <h2>Two legs, two tests, each independently breakable</h2>
 *
 * <ol>
 *   <li>{@link #theRelayAlsoPublishesEachEventToKafka} covers the PRODUCER leg: draining the relay in
 *       {@code both} mode delivers in process AND lands the event on its topic. Remove the Kafka sink
 *       from {@code PublishOutboxEventsService} and it goes red.</li>
 *   <li>{@link #anEventDeliveredOnlyThroughKafkaIsConsumedByAnotherCapabilityExactlyOnce} covers the
 *       CONSUMER leg and the dedup: an event put on Kafka <b>with no in-process delivery at all</b> is
 *       consumed by the listener, applied by a different capability (Order), and applied exactly once
 *       however many times it is redelivered. Unregister the listener and it never reaches PAID;
 *       break the inbox and the redelivery double-applies.</li>
 * </ol>
 *
 * Deliberately NOT {@code @Transactional}: the commits here are real (the in-process path and the
 * Kafka consumer commit in their own transactions), so each test registers its own merchant and
 * scopes its queries to it, like {@code EventDeliveryIntegrationTest}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, DualPathRelayIntegrationTest.KafkaTestContainer.class})
@ActiveProfiles("dev")
@TestPropertySource(properties = "paymesh.events.delivery.mode=both")
class DualPathRelayIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTestContainer {

        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka:4.3.1");
        }
    }

    private static final Instant CREATED_AT = Instant.parse("2026-08-25T10:15:30Z");
    private static final Instant PROVIDER_EVENT = Instant.parse("2026-08-25T11:00:00Z");
    private static final long ORDER_AMOUNT_MINOR = 4000;
    private static final String PROVIDER = "SIMULATOR";
    private static final String PAYMENT_INTENT_TOPIC = "payment-intent-events";

    /** Generous, because a pattern-subscribed consumer must discover a just-created topic and join a
     * group before it reads anything; that is metadata refresh plus a rebalance, several seconds on a
     * cold broker. The assertion is on the outcome, not the latency, so a wide bound costs nothing. */
    private static final long AWAIT_MILLIS = 45_000;

    @Autowired
    private PublishOutboxEventsService relay;

    @Autowired
    private KafkaEventPublisher kafkaPublisher;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CreatePaymentIntentService createPaymentIntentService;

    @Autowired
    private AttachPaymentMethodService attachPaymentMethodService;

    @Autowired
    private ConfirmPaymentIntentService confirmPaymentIntentService;

    @Autowired
    private RecordProviderCallbackService callbacks;

    @Autowired
    private MerchantRepository merchants;

    @Autowired
    private OrderRepository orders;

    @Autowired
    private JdbcClient jdbc;

    // --- PRODUCER LEG -----------------------------------------------------------------------------

    /**
     * THE PRODUCER LEG: the relay's Kafka pass ({@code relayToKafka}) puts each event on its topic,
     * keyed by the aggregate.
     * <p>
     * <b>Sabotage that must turn this red:</b> make {@code relayToKafka} skip the
     * {@code kafkaPublisher.publish(event)} call. Nothing is ever consumed off the topic and the poll
     * below times out.
     */
    @Test
    void theRelayAlsoPublishesEachEventToKafka() {
        // The suite shares one database, so by now the Kafka backlog holds every other test's events
        // (they ran in in-process mode, so their kafka_published_at is null). Neutralize it, so the
        // pass below publishes only THIS test's fresh events rather than draining thousands onto the
        // broker. This is the Kafka-track twin of EventDeliveryIntegrationTest's drain-to-fixpoint.
        markExistingKafkaBacklogDone();

        Fixture fixture = paymentSucceededFor(existingMerchant());

        drainKafka();

        // The topic carries every PAYMENT_INTENT event (created, confirmed, ...succeeded), so match on
        // the one this test produced rather than the first that arrives.
        ConsumerRecord<String, String> record =
            consumePaymentSucceeded(PAYMENT_INTENT_TOPIC, fixture.merchantId());

        assertThat(record.key())
            .as("the WIRE key is the aggregate, which is what buys per-aggregate ordering (ADR-036)")
            .isEqualTo(fixture.intentId().value());
        assertThat(json.readValue(record.value(), EventEnvelope.class).merchantId())
            .isEqualTo(fixture.merchantId().value());
    }

    // --- CONSUMER LEG + DEDUP ---------------------------------------------------------------------

    /**
     * THE HEADLINE: an event produced by one capability, delivered ONLY through Kafka, is consumed by
     * ANOTHER capability and applied exactly once — even when Kafka redelivers it.
     * <p>
     * The event is generated by the real Payment flow (so its payload is the real one every consumer
     * reads) but the relay is never drained, so the in-process path never runs: the only thing that
     * can move the order to PAID is the Kafka listener feeding Order's handler. Then the same record
     * is published a second time, and the inbox is the only thing standing between one application and
     * two.
     * <p>
     * <b>Sabotage that must turn this red:</b> (1) unregister {@code KafkaEventListener} — the order
     * never reaches PAID; (2) remove the {@code markProcessed} guard in {@code EventDispatcher} — the
     * second publish drives a second PAID transition and the count below is 2.
     */
    @Test
    void anEventDeliveredOnlyThroughKafkaIsConsumedByAnotherCapabilityExactlyOnce() {
        Fixture fixture = paymentSucceededFor(existingMerchant());

        assertThat(status(fixture))
            .as("nothing has delivered the event yet — the relay was not drained")
            .isEqualTo(OrderStatus.PENDING);

        OutboxEvent event = unpublishedPaymentSucceeded(fixture.merchantId());

        // Straight onto the broker, bypassing the in-process dispatcher entirely.
        kafkaPublisher.publish(event);

        awaitOrderPaid(fixture);

        assertThat(orderInboxRows(event.eventId()))
            .as("Order's consumer applied it once")
            .isEqualTo(1);
        assertThat(paidHistoryRows(fixture))
            .as("one transition, one timeline row")
            .isEqualTo(1);

        // The same record again, as an at-least-once broker would redeliver it.
        kafkaPublisher.publish(event);

        awaitStableOrderInboxRow(event.eventId());

        assertThat(paidHistoryRows(fixture))
            .as("the inbox made the redelivery a no-op — still exactly one application")
            .isEqualTo(1);
        assertThat(status(fixture)).isEqualTo(OrderStatus.PAID);
    }

    // --- fixture ----------------------------------------------------------------------------------

    private record Fixture(MerchantId merchantId, OrderId orderId, PaymentIntentId intentId) {
    }

    /** A merchant, an order, a confirmed AUTOMATIC intent, and a provider SUCCEEDED callback — which
     * leaves a complete {@code payment.succeeded} in the outbox and the order still PENDING (nothing
     * has dispatched it). Mirrors {@code EventDeliveryIntegrationTest}'s headline setup. */
    private Fixture paymentSucceededFor(MerchantId merchantId) {
        Order order = orders.save(Order.create(
            OrderId.generate(), merchantId, null, "ORDER-" + UUID.randomUUID(),
            ORDER_AMOUNT_MINOR, "INR", null, Map.of(), null, CREATED_AT
        ));

        PaymentIntent intent = createPaymentIntentService.create(new CreatePaymentIntentCommand(
            merchantId, order.orderId().value(), null, ORDER_AMOUNT_MINOR, "INR",
            CaptureMethod.AUTOMATIC, null, Map.of()
        ));

        attachPaymentMethodService.attach(merchantId, intent.paymentIntentId(), PaymentMethodType.CARD);
        confirmPaymentIntentService.confirm(new ConfirmPaymentIntentCommand(
            merchantId, intent.paymentIntentId(), null, null
        ));

        callbacks.record(new RecordProviderCallbackCommand(
            PROVIDER,
            new ProviderEvent(
                "evt-succeed-" + UUID.randomUUID(), PROVIDER_EVENT, intent.paymentIntentId().value(),
                null, ProviderOutcome.SUCCEEDED, null, ORDER_AMOUNT_MINOR, null, null, null
            ),
            payloadHash()
        ));

        return new Fixture(merchantId, order.orderId(), intent.paymentIntentId());
    }

    /** The complete {@code payment.succeeded} the flow above left in the outbox, rebuilt into the
     * event the relay would have published. Read straight from the row so its {@code eventId} — the
     * inbox dedup key — is the real one, not a fresh mint. */
    private OutboxEvent unpublishedPaymentSucceeded(MerchantId merchantId) {
        return jdbc.sql("""
                select event_id, aggregate_type, aggregate_id, event_version,
                       payload::text as payload_json, occurred_at
                  from outbox_events
                 where merchant_id = ? and event_type = 'payment.succeeded'
                """)
            .param(merchantId.value())
            .query((rs, rowNum) -> new OutboxEvent(
                EventId.from(rs.getString("event_id")),
                merchantId,
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                "payment.succeeded",
                rs.getInt("event_version"),
                readPayload(rs.getString("payload_json")),
                rs.getObject("occurred_at", OffsetDateTime.class).toInstant()
            ))
            .single();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(String payloadJson) {
        return json.readValue(payloadJson, Map.class);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private void drainKafka() {
        // The Kafka pass to a fixed point. Bounded per pass, so a few iterations clear this test's
        // handful of fresh events once the shared backlog has been neutralized.
        while (relay.relayToKafka().published() > 0) {
            // keep going
        }
    }

    /** Marks every currently Kafka-unpublished row as done, so {@link #drainKafka} publishes only the
     * events created after this call. Uses occurred_at as a harmless non-null stamp. */
    private void markExistingKafkaBacklogDone() {
        jdbc.sql("update outbox_events set kafka_published_at = occurred_at where kafka_published_at is null")
            .update();
    }

    private void awaitOrderPaid(Fixture fixture) {
        Instant deadline = Instant.now().plusMillis(AWAIT_MILLIS);

        while (Instant.now().isBefore(deadline)) {
            if (status(fixture) == OrderStatus.PAID) {
                return;
            }
            sleep(250);
        }

        throw new AssertionError("The order never reached PAID through the Kafka consumer");
    }

    /** Waits until Order's inbox row exists, then a beat more, so the "still one" assertion is made
     * after the second delivery has had time to (fail to) double-apply rather than before it lands. */
    private void awaitStableOrderInboxRow(EventId eventId) {
        Instant deadline = Instant.now().plusMillis(AWAIT_MILLIS);

        while (Instant.now().isBefore(deadline) && orderInboxRows(eventId) < 1) {
            sleep(250);
        }
        sleep(1_000);
    }

    private ConsumerRecord<String, String> consumePaymentSucceeded(String topic, MerchantId merchantId) {
        try (Consumer<String, String> consumer =
                 consumerFactory.createConsumer("dual-path-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));

            Instant deadline = Instant.now().plusMillis(AWAIT_MILLIS);

            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> records = consumer.poll(java.time.Duration.ofMillis(500));

                for (ConsumerRecord<String, String> record : records.records(topic)) {
                    EventEnvelope envelope = json.readValue(record.value(), EventEnvelope.class);
                    // The topic accumulates records across this class's tests and every PAYMENT_INTENT
                    // event type; take this merchant's payment.succeeded.
                    if (envelope.merchantId().equals(merchantId.value())
                        && envelope.eventType().equals("payment.succeeded")) {
                        return record;
                    }
                }
            }

            throw new AssertionError(
                "No payment.succeeded on " + topic + " for " + merchantId.value()
                    + " within " + AWAIT_MILLIS + "ms"
            );
        }
    }

    private OrderStatus status(Fixture fixture) {
        return orders.findByOrderId(fixture.merchantId(), fixture.orderId()).orElseThrow().status();
    }

    private long orderInboxRows(EventId eventId) {
        return jdbc
            .sql("select count(*) from processed_events where consumer_name = ? and event_id = ?")
            .params("order.payment-succeeded", eventId.value())
            .query(Long.class)
            .single();
    }

    private long paidHistoryRows(Fixture fixture) {
        return jdbc.sql("""
                select count(*) from order_state_history
                 where merchant_id = ? and order_id = ? and to_status = 'PAID'
                """)
            .params(fixture.merchantId().value(), fixture.orderId().value())
            .query(Long.class)
            .single();
    }

    private MerchantId existingMerchant() {
        return merchants.save(Merchant.register(
            MerchantId.generate(),
            "Paymesh Test Co",
            UUID.randomUUID() + "@paymesh.test",
            "IN",
            "INR",
            CREATED_AT
        ).activate(CREATED_AT)).merchantId();
    }

    private static String payloadHash() {
        return (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while awaiting", interruption);
        }
    }
}
