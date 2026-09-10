package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * The broker sink the dual-path relay publishes to alongside the in-process dispatcher (ADR-037).
 *
 * <h2>Why this is a port, when the other sink is not</h2>
 *
 * {@link PublishOutboxEventsService} is a plain application object, and the same rule that gives the
 * outbox its {@link OutboxReader}/{@link OutboxWriter} interfaces keeps it that way: the relay must
 * not import the Kafka infrastructure it drives. The in-process sink needs no such port because
 * {@link EventDispatcher} is already framework-free and lives in this layer; the Kafka sink cannot,
 * because it holds a {@code KafkaTemplate}. So the seam is here and the single implementation
 * ({@code KafkaEventPublisher}, ADR-036) lives in {@code infrastructure}, exactly as
 * {@code JpaOutboxReader} does.
 */
public interface EventPublisher {

    /**
     * Puts the event on the broker and blocks until it is acknowledged, throwing if it is not.
     * <p>
     * The relay treats a throw here as "the Kafka sink did not take it": it leaves
     * {@code published_at} null and retries on the next pass, and — because a broker being down is a
     * global, self-healing condition rather than a poisoned event — that retry does <b>not</b>
     * consume the dead-letter budget (ADR-037 §3). The in-process dispatch that already ran is
     * deduped by each consumer's inbox on the retry.
     */
    void publish(OutboxEvent event);
}
