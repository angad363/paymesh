package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import com.paymesh.shared.outbox.application.ProcessedEventRepository;
import com.paymesh.shared.outbox.domain.EventId;

import java.time.Instant;

/**
 * PostgreSQL-backed implementation of the inbox port.
 * <p>
 * There is no {@code @Transactional} here, and its absence is the design. The insert joins whatever
 * transaction the dispatcher already opened, so the claim commits with the work it authorized or not
 * at all.
 */
public final class JpaProcessedEventRepository implements ProcessedEventRepository {

    private final SpringDataProcessedEventRepository processedEvents;

    public JpaProcessedEventRepository(SpringDataProcessedEventRepository processedEvents) {
        this.processedEvents = processedEvents;
    }

    /**
     * One statement, no preceding read. The row count is the database's answer to "am I the one that
     * has to handle this?" -- 1 means yes, 0 means an earlier delivery already did. Checking first
     * and trusting the check is the bug: under concurrent delivery every caller reads "absent" and
     * every caller applies the event.
     */
    @Override
    public boolean markProcessed(
        String consumerName,
        EventId eventId,
        String eventType,
        Instant processedAt
    ) {
        return processedEvents.claim(consumerName, eventId.value(), eventType, processedAt) == 1;
    }
}
