package com.paymesh.shared.outbox.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/**
 * Spring Data access to the outbox_events table: producers append, the relay claims and stamps.
 * <p>
 * BOTH RELAY QUERIES ARE NATIVE, AND NEITHER IS BY ACCIDENT. {@code OutboxEventJpaEntity} is
 * {@code @Immutable} (ADR-010 measured why), so Hibernate has removed it from state management
 * entirely -- an HQL update against it, or an assignment to a mapped {@code publishedAt}, would be
 * silently dropped rather than refused. Native SQL goes straight to the database and is unaffected.
 * <p>
 * The claim query returns the entity even though {@code published_at} is not mapped on it: a native
 * result simply ignores columns the entity does not declare, which is exactly the outcome wanted
 * here -- the relay needs the predicate, not the value.
 */
public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, String> {

    /**
     * The backlog, oldest first.
     * <p>
     * {@code published_at IS NULL} plus {@code ORDER BY occurred_at} is precisely what
     * {@code idx_outbox_events_unpublished} is built for -- V7 calls it "THE RELAY'S CLAIM QUERY"
     * and made it partial so it holds only the backlog and shrinks back to nothing once the relay
     * keeps up.
     * <p>
     * No {@code FOR UPDATE SKIP LOCKED}. There is one relay instance, and locking rows a
     * single-threaded reader is about to process serially would buy nothing. It is the upgrade path
     * if a second instance ever runs; the inbox already makes concurrent delivery safe rather than
     * merely unlikely, so this would be an efficiency change and not a correctness one.
     */
    @Query(
        value = """
            select *
              from outbox_events
             where published_at is null
             order by occurred_at asc
             limit :limit
            """,
        nativeQuery = true
    )
    List<OutboxEventJpaEntity> findUnpublished(@Param("limit") int limit);

    /**
     * Stamps delivery. {@code published_at is null} in the WHERE makes it a compare-and-swap: an
     * event some other pass already published is left with its original timestamp rather than having
     * it rewritten.
     */
    @Modifying
    @Query(
        value = """
            update outbox_events
               set published_at = :publishedAt
             where event_id = :eventId
               and published_at is null
            """,
        nativeQuery = true
    )
    int markPublished(@Param("eventId") String eventId, @Param("publishedAt") Instant publishedAt);
}
