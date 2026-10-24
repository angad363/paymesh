package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The round trip ADR-036 is verified by: an event published by {@link KafkaEventPublisher} comes
 * back off a real broker as the same event.
 *
 * <h2>Against a real broker, and only this test pays for one</h2>
 *
 * The container is declared here rather than in {@link TestcontainersConfiguration} on purpose. Put
 * beside the shared PostgreSQL it would start a broker for every context in the suite, to serve one
 * test -- and the point of PR 1 is precisely that nothing else needs Kafka yet.
 * <p>
 * It is a real broker rather than a mock because everything this test can get wrong lives below the
 * Java API: serializer configuration, the record key, the offset a fresh consumer group starts
 * from. A mocked producer would assert that we called a method we already know we call.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, KafkaEventRoundTripTest.KafkaTestContainer.class})
@ActiveProfiles("dev")
class KafkaEventRoundTripTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTestContainer {

        /**
         * KRaft, no ZooKeeper -- {@code org.testcontainers.kafka.KafkaContainer} runs the Apache
         * image in combined broker+controller mode, the same shape as the single-broker service in
         * {@code docker-compose.yml}. {@code @ServiceConnection} overrides
         * {@code spring.kafka.bootstrap-servers} with the container's mapped port, exactly as the
         * PostgreSQL container overrides the datasource.
         */
        @Bean
        @ServiceConnection
        KafkaContainer kafkaContainer() {
            return new KafkaContainer("apache/kafka:4.3.1");
        }
    }

    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private KafkaEventPublisher publisher;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Tracer tracer;

    @Test
    void publishedEventComesBackOffTheBrokerUnchanged() {
        OutboxEvent event = new OutboxEvent(
            EventId.generate(),
            MerchantId.generate(),
            "PAYMENT_INTENT",
            "pi_" + UUID.randomUUID(),
            "payment.succeeded",
            3,
            Map.of("amountMinor", 1250, "currency", "GBP", "capturedAt", "2026-08-24T10:15:30Z"),
            Instant.parse("2026-08-24T10:15:30Z")
        );

        publisher.publish(event);

        ConsumerRecord<String, String> record = consumeOne("payment-intent-events");

        // The key is what buys per-aggregate ordering, so it is asserted separately from the body:
        // a body that survives on the wrong partition is not the guarantee ADR-036 claims.
        assertThat(record.key()).isEqualTo(event.aggregateId());

        EventEnvelope received = json.readValue(record.value(), EventEnvelope.class);

        assertThat(received.eventVersion()).isEqualTo(3);
        assertThat(received.payload()).containsEntry("amountMinor", 1250);
        assertThat(received.toEvent()).isEqualTo(event);
    }

    /**
     * ADR-052's acceptance core for the asynchronous hop, asserted rather than demonstrated live:
     * a request's trace follows its event across Kafka. With {@code spring.kafka.template.observation-enabled}
     * on, the observation-wrapped {@code KafkaTemplate} injects the W3C {@code traceparent} into the
     * record's HEADERS (the body / {@link EventEnvelope} is untouched, ADR-036). So an event published
     * inside a span carries that span's trace id out, and the consumer that reads it links to the same
     * trace -- which is what "one connected trace across services" means on the leg the outbox adds.
     * <p>
     * The proof is the trace id, not merely a header's presence: a {@code traceparent} rooted at the
     * send instead of the originating span would still be a header but a different trace. This starts a
     * span, publishes within its scope, and asserts the header carries THAT span's 32-hex trace id.
     * If the observation flag were removed, no header is injected and this fails -- the test the
     * invariant is worth.
     */
    @Test
    void aTraceFollowsItsEventAcrossKafkaInTheTraceparentHeader() {
        // Its own aggregate type -> its own topic, so this test shares the suite's single broker with
        // the round-trip test above but not its topic (whose consumer demands exactly one record).
        OutboxEvent event = new OutboxEvent(
            EventId.generate(),
            MerchantId.generate(),
            "TRACE_PROBE",
            "tp_" + UUID.randomUUID(),
            "payment.succeeded",
            3,
            Map.of("amountMinor", 4200, "currency", "GBP"),
            Instant.parse("2026-08-24T10:15:30Z")
        );

        Span request = tracer.nextSpan().name("authorize");
        String expectedTraceId;
        try (Tracer.SpanInScope scope = tracer.withSpan(request.start())) {
            expectedTraceId = request.context().traceId();
            // publish() blocks on the broker ack on THIS thread, inside the span's scope, so the
            // KafkaTemplate's observation sees `request` as its parent and stamps its trace id.
            publisher.publish(event);
        } finally {
            request.end();
        }

        ConsumerRecord<String, String> record = consumeOne("trace-probe-events");

        var traceparent = record.headers().lastHeader("traceparent");
        assertThat(traceparent)
            .as("traceparent header must be injected when template observation is on")
            .isNotNull();
        // W3C format: 00-<32 hex traceId>-<16 hex spanId>-<flags>. The trace id is the linkage.
        assertThat(new String(traceparent.value(), StandardCharsets.UTF_8))
            .as("the relayed event must carry the publishing request's trace id")
            .contains(expectedTraceId);
    }

    /**
     * The additive half of the compatibility rule, asserted rather than trusted.
     * <p>
     * A producer that has moved ahead of this consumer sends fields it does not know -- a new
     * payload key, and one day a new envelope field. If either threw, "additive changes need no
     * coordinated deploy" would be false and every addition would become a lockstep release.
     */
    @Test
    void anEnvelopeWithFieldsThisVersionDoesNotKnowStillParses() {
        String fromAFutureProducer = """
            {
              "eventId": "evt_00000000-0000-4000-8000-000000000001",
              "eventType": "payment.succeeded",
              "eventVersion": 3,
              "occurredAt": "2026-08-24T10:15:30Z",
              "merchantId": "mrc_00000000-0000-4000-8000-000000000002",
              "aggregateType": "PAYMENT_INTENT",
              "aggregateId": "pi_00000000-0000-4000-8000-000000000003",
              "payload": { "amountMinor": 1250, "settledInstantly": true },
              "traceId": "a-field-added-after-this-consumer-shipped"
            }
            """;

        EventEnvelope received = json.readValue(fromAFutureProducer, EventEnvelope.class);

        assertThat(received.eventVersion()).isEqualTo(3);
        assertThat(received.toEvent().payload()).containsEntry("amountMinor", 1250);
    }

    /**
     * A fresh group id per call, so the test never depends on a committed offset -- and so the
     * {@code auto-offset-reset: earliest} in {@code application.yaml} is doing real work here: with
     * Kafka's own default of {@code latest} a group created after the send would read nothing.
     */
    private ConsumerRecord<String, String> consumeOne(String topic) {
        try (Consumer<String, String> consumer =
                 consumerFactory.createConsumer("round-trip-" + UUID.randomUUID(), null)) {
            consumer.subscribe(List.of(topic));

            // Polled in a loop rather than once: the first poll after a subscribe usually returns
            // empty while the group is still being assigned its partitions, and a single poll would
            // make this test flaky for a reason that has nothing to do with what it asserts.
            Instant deadline = Instant.now().plus(POLL_TIMEOUT);

            while (Instant.now().isBefore(deadline)) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                if (!records.isEmpty()) {
                    assertThat(records.count())
                        .as("exactly one record on %s", topic)
                        .isEqualTo(1);

                    return records.records(topic).iterator().next();
                }
            }

            throw new AssertionError("No record arrived on " + topic + " within " + POLL_TIMEOUT);
        }
    }
}
