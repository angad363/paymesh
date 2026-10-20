package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * PostgreSQL-backed implementation of the OutboxWriter port.
 * <p>
 * There is no {@code @Transactional} here, and its absence is the design (ADR-010). The insert
 * joins whatever transaction the caller already opened, so it commits with the state change or not
 * at all.
 */
public final class JpaOutboxWriter implements OutboxWriter {

    private final SpringDataOutboxRepository events;

    public JpaOutboxWriter(SpringDataOutboxRepository events) {
        this.events = events;
    }

    /**
     * Flushed rather than left for the commit so a rejected event (a merchant that does not exist,
     * a payload that will not serialize) fails at the line that wrote it, with a stack trace naming
     * the producer, instead of surfacing as an opaque commit-time failure.
     */
    @Override
    public void append(OutboxEvent event) {
        events.saveAndFlush(OutboxEventJpaMapper.toEntity(event));
    }
}
