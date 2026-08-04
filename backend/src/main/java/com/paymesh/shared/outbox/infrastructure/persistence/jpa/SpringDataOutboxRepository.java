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
     * {@code dead_lettered_at is null} (V21) is the second half of that predicate and the reason a
     * poisoned event stops blocking its aggregate: once the relay has given up, the row is skipped
     * here rather than returned at the head of every pass forever. V21 rebuilt the partial index to
     * match, so this stays an index-only range scan rather than an index scan plus a filter.
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
               and dead_lettered_at is null
             order by occurred_at asc
             limit :limit
            """,
        nativeQuery = true
    )
    List<OutboxEventJpaEntity> findUnpublished(@Param("limit") int limit);

    /**
     * Records one failed delivery attempt and, on the attempt that exhausts the budget, gives up.
     *
     * <h2>ONE STATEMENT, AND THE INCREMENT AND THE DECISION ARE THE SAME EXPRESSION</h2>
     *
     * {@code attempt_count + 1} appears in both the SET and the CASE, so the value compared against
     * the budget is by construction the value being stored. Reading the counter, deciding in Java
     * and writing back would be the same logic across two statements with a window between them, and
     * a second relay instance in that window would burn one attempt while recording none.
     * <p>
     * {@code published_at is null} in the WHERE makes it a compare-and-swap on the same column
     * {@link #markPublished} swaps: an event that some other pass delivered between the dispatch
     * failing here and this statement running is left published rather than being dead-lettered on
     * the strength of a failure that has since been superseded.
     *
     * @param maxAttempts the retry budget. The row is dead-lettered on the attempt where the
     *                    incremented count REACHES it, so a budget of 1 gives up after one failure
     *                    and there is no value that means "never give up" -- that is a
     *                    configuration decision, made in {@code OutboxRelayProperties}, not a magic
     *                    number smuggled in here.
     * @return rows updated: 0 when the event was published or already gone, 1 otherwise. The caller
     *         does not branch on it; it exists so a test can prove the compare-and-swap fired.
     */
    @Modifying
    @Query(
        value = """
            update outbox_events
               set attempt_count = attempt_count + 1,
                   last_attempt_at = :attemptedAt,
                   last_error = :error,
                   dead_lettered_at = case
                       when attempt_count + 1 >= :maxAttempts then :attemptedAt
                       else dead_lettered_at
                   end
             where event_id = :eventId
               and published_at is null
            """,
        nativeQuery = true
    )
    int recordFailedAttempt(
        @Param("eventId") String eventId,
        @Param("attemptedAt") Instant attemptedAt,
        @Param("error") String error,
        @Param("maxAttempts") int maxAttempts
    );

    /**
     * {@code min(occurred_at)} over the deliverable backlog -- SDD section 24's "oldest unpublished
     * event age", which is the one number that distinguishes a relay keeping up from a relay that
     * has silently stopped.
     * <p>
     * Deliberately EXCLUDES dead-lettered rows. Their age would only ever grow, so including them
     * would pin this metric permanently past any threshold and turn the alert it feeds into
     * background noise -- the failure mode where an always-red signal is the same as no signal.
     * Those rows are counted separately by {@link #countDeadLettered()}.
     *
     * @return null when the backlog is empty, which is the healthy state rather than a missing value
     */
    @Query(
        value = """
            select min(occurred_at)
              from outbox_events
             where published_at is null
               and dead_lettered_at is null
            """,
        nativeQuery = true
    )
    Instant oldestUnpublishedOccurredAt();

    /** How many events the relay has given up on. Non-zero means undelivered money events exist. */
    @Query(
        value = "select count(*) from outbox_events where dead_lettered_at is not null",
        nativeQuery = true
    )
    long countDeadLettered();

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
