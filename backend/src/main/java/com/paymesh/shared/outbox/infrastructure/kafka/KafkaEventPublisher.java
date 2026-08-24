package com.paymesh.shared.outbox.infrastructure.kafka;

import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Puts one {@link OutboxEvent} on its topic, as JSON, keyed by its aggregate (ADR-036).
 *
 * <h2>The send BLOCKS, and that is the whole design</h2>
 *
 * {@code KafkaTemplate.send} is asynchronous: it returns as soon as the record is in the producer's
 * local buffer, long before any broker has it. A relay that treated that return as success would
 * stamp {@code published_at} on an event sitting in a buffer that a crash then discards -- an event
 * committed to the database and lost on the way out, which is the first half of the governing
 * invariant failing. So this waits for the broker's acknowledgement and throws if it does not come,
 * because the relay's contract is already "throw and I will retry you" ({@code PublishOutboxEvents},
 * ADR-025). At-least-once is bought here, by being willing to block.
 * <p>
 * Nothing calls this yet. PR 2 (the dual-path relay) is what hands it events.
 */
public final class KafkaEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /**
     * The backstop, not the real deadline. The producer's own {@code delivery.timeout.ms} (two
     * minutes by default) is what bounds retries; this only stops a relay thread waiting forever if
     * the future is never completed at all. Deliberately not a property: no environment has a reason
     * to want a different number, and a knob nobody turns is configuration debt.
     */
    private static final Duration ACKNOWLEDGEMENT_TIMEOUT = Duration.ofMinutes(3);

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, ObjectMapper json) {
        this.kafka = kafka;
        this.json = json;
    }

    /**
     * @throws IllegalArgumentException when the event cannot be addressed to a topic
     * @throws IllegalStateException when the broker did not acknowledge the record. The caller is
     *     expected to leave the outbox row unpublished so the next pass tries again; a duplicate
     *     from a send that actually succeeded is a no-op at the consumer's inbox.
     */
    public void publish(OutboxEvent event) {
        EventEnvelope envelope = EventEnvelope.from(event);
        String topic = envelope.topic();

        try {
            kafka.send(topic, envelope.partitionKey(), json.writeValueAsString(envelope))
                .get(ACKNOWLEDGEMENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interruption) {
            // Restore the flag before leaving: swallowing it would strand a shutting-down relay
            // thread in the next blocking call it makes.
            Thread.currentThread().interrupt();

            throw new IllegalStateException(
                "Interrupted publishing event " + envelope.eventId() + " to " + topic, interruption
            );
        } catch (Exception failure) {
            throw new IllegalStateException(
                "Publishing event " + envelope.eventId() + " to " + topic + " failed", failure
            );
        }

        log.debug(
            "Published event to Kafka topic={} key={} eventId={} eventType={} version={}",
            topic, envelope.partitionKey(), envelope.eventId(), envelope.eventType(),
            envelope.eventVersion()
        );
    }
}
