package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Translates the domain event into the persistence row. One direction only: nothing reads the
 * outbox yet, and a toDomain() with no caller would be a guess about what a relay wants.
 */
public final class OutboxEventJpaMapper {

    private OutboxEventJpaMapper() {
    }

    public static OutboxEventJpaEntity toEntity(OutboxEvent event) {
        return new OutboxEventJpaEntity(
            event.eventId().value(),
            event.merchantId().value(),
            event.aggregateType(),
            event.aggregateId(),
            event.eventType(),
            event.eventVersion(),
            event.payload(),
            event.occurredAt()
        );
    }
}
