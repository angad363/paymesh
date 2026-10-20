package com.paymesh.shared.outbox.infrastructure.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.util.backoff.ExponentialBackOff;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A consumer that fails more times than the default bound (10) and then succeeds must still apply
 * a {@code settlement.batch_cut}; any other event type is still given up on after the bound.
 * <p>
 * Drives the handler the way the container does: on a listener exception, hand the record to
 * {@code handleRemaining}; the record is redelivered (here: looped) unless it was recovered/skipped.
 * Fails without the per-type back off: the batch_cut would be skipped after 10 attempts.
 */
class KafkaEventErrorHandlerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void batchCutIsRetriedPastTheDefaultBoundUntilItSucceeds() {
        Outcome outcome = run("settlement.batch_cut", 25);

        assertThat(outcome.applied).isTrue();
        assertThat(outcome.skipped.get()).isZero();
        assertThat(outcome.attempts.get()).isEqualTo(26);
    }

    @Test
    void anyOtherEventTypeStillGivesUpAfterTheBound() {
        Outcome outcome = run("payment.succeeded", 25);

        assertThat(outcome.applied).isFalse();
        assertThat(outcome.skipped.get()).isEqualTo(1);
        assertThat(outcome.attempts.get()).isLessThan(26);
    }

    private Outcome run(String eventType, int failures) {
        ExponentialBackOff fast = new ExponentialBackOff(1L, 1.0);
        fast.setMaxElapsedTime(Long.MAX_VALUE);

        AtomicInteger skipped = new AtomicInteger();
        DefaultErrorHandler handler =
            KafkaEventErrorHandler.create(JSON, (record, consumer, failure) -> skipped.incrementAndGet(), fast);

        String body = JSON.writeValueAsString(new EventEnvelope(
            "evt_x", eventType, 1, null, null, "SETTLEMENT_BATCH", "stl_x", java.util.Map.of()
        ));
        ConsumerRecord<String, String> record = new ConsumerRecord<>("settlement-batch-events", 0, 0L, "k", body);
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);

        Outcome outcome = new Outcome(skipped);

        for (int i = 0; i < 100 && !outcome.applied && skipped.get() == 0; i++) {
            outcome.attempts.incrementAndGet();

            if (outcome.attempts.get() > failures) {
                outcome.applied = true;
                break;
            }

            try {
                handler.handleRemaining(new IllegalStateException("poisoned"), List.of(record), consumer, container);
            } catch (RuntimeException redeliver) {
                // what the container does: the record comes round again
            }
        }

        return outcome;
    }

    private static final class Outcome {
        final AtomicInteger skipped;
        final AtomicInteger attempts = new AtomicInteger();
        boolean applied;

        Outcome(AtomicInteger skipped) {
            this.skipped = skipped;
        }
    }
}
