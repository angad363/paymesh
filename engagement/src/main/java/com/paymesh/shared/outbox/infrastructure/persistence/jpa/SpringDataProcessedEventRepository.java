package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Spring Data access to the processed_events inbox.
 * <p>
 * ONE HAND-WRITTEN NATIVE INSERT, AND NO {@code save}. Spring Data's save would merge -- a SELECT
 * then an INSERT or an UPDATE -- which is a read-then-write, and a read-then-write is exactly what
 * an inbox must not be: two concurrent deliveries of one event would both read "absent" and both
 * handle it. {@code ON CONFLICT DO NOTHING} plus the returned row count makes the database the
 * arbiter, the same shape {@code JpaIdempotencyRepository} uses for public writes (ADR-009).
 * <p>
 * The row count is the entire answer, which is why nothing else in this interface reads the table.
 */
public interface SpringDataProcessedEventRepository
    extends JpaRepository<ProcessedEventJpaEntity, ProcessedEventJpaId> {

    /**
     * @return 1 when this call claimed the event, 0 when some earlier delivery already had it. Never
     *         anything else: the primary key admits one row per pair.
     */
    @Modifying
    @Query(
        value = """
            insert into processed_events (consumer_name, event_id, event_type, processed_at)
            values (:consumerName, :eventId, :eventType, :processedAt)
            on conflict (consumer_name, event_id) do nothing
            """,
        nativeQuery = true
    )
    int claim(
        @Param("consumerName") String consumerName,
        @Param("eventId") String eventId,
        @Param("eventType") String eventType,
        @Param("processedAt") Instant processedAt
    );
}
