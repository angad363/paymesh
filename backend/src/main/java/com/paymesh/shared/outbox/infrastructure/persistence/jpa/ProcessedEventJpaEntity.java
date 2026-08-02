package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Persistence model for the processed_events table (V14__create_processed_events.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift, which is
 * the only reason this class exists at all: nothing writes through it.
 * <p>
 * THE INSERT IS A HAND-WRITTEN NATIVE STATEMENT, not {@code save}. See
 * {@code SpringDataProcessedEventRepository} -- an inbox that reads before it writes is not an
 * inbox. So this entity is a schema assertion and a read shape, and {@code @Immutable} says the
 * obvious thing about a table whose rows are facts: a consumer either processed an event or it did
 * not, and there is no legitimate UPDATE to that.
 */
@Entity
@Immutable
@Table(name = "processed_events")
public class ProcessedEventJpaEntity {

    @EmbeddedId
    private ProcessedEventJpaId id;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    /** Required by JPA. Not for application use. */
    protected ProcessedEventJpaEntity() {
    }

    public ProcessedEventJpaId id() {
        return id;
    }

    public String eventType() {
        return eventType;
    }

    public Instant processedAt() {
        return processedAt;
    }
}
