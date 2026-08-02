package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import com.paymesh.shared.outbox.application.OutboxReader;
import com.paymesh.shared.outbox.application.UnpublishedEvent;
import com.paymesh.shared.outbox.domain.EventId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL-backed implementation of the relay's read/stamp port.
 * <p>
 * NOTE WHAT IS NOT HERE: any conversion to {@code OutboxEvent}. The rows are handed over exactly as
 * stored, because validating them here would put the failure OUTSIDE the relay's per-item try/catch
 * -- the bug open item 2 describes in both existing sweeps, where one unmappable row disables a job
 * permanently. See {@code UnpublishedEvent}.
 * <p>
 * There is no {@code @Transactional}. {@code findUnpublished} is a plain read; {@code markPublished}
 * is {@code @Modifying} and joins the transaction the relay opened around it.
 */
public final class JpaOutboxReader implements OutboxReader {

    private final SpringDataOutboxRepository events;

    public JpaOutboxReader(SpringDataOutboxRepository events) {
        this.events = events;
    }

    @Override
    public List<UnpublishedEvent> findUnpublished(int limit) {
        return events.findUnpublished(limit).stream()
            .map(JpaOutboxReader::toRow)
            .toList();
    }

    @Override
    public void markPublished(EventId eventId, Instant publishedAt) {
        events.markPublished(eventId.value(), publishedAt);
    }

    /**
     * Column-for-column, with no validation of any kind -- that is the contract.
     * <p>
     * A null payload is mapped to an empty map rather than passed through: {@code payload} is
     * {@code NOT NULL} in the schema, so a null here would be corruption, and letting it reach a
     * consumer as a NullPointerException deep inside a handler is a worse way to learn that than an
     * empty map the handler refuses.
     */
    private static UnpublishedEvent toRow(OutboxEventJpaEntity entity) {
        Map<String, Object> payload = entity.payload();

        return new UnpublishedEvent(
            entity.eventId(),
            entity.merchantId(),
            entity.aggregateType(),
            entity.aggregateId(),
            entity.eventType(),
            entity.eventVersion(),
            payload == null ? Map.of() : payload,
            entity.occurredAt()
        );
    }
}
