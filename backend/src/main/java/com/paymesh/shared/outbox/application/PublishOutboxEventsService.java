package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The relay: reads the outbox backlog, delivers it, and records what was delivered (ADR-016).
 *
 * <h2>It is a plain object, and the scheduler is somewhere else</h2>
 *
 * No {@code @Scheduled}, no {@code @Component}, no Spring annotation of any kind.
 * {@code OutboxRelay} owns the timer and does nothing but call {@link #publish()} and log the
 * result, so every rule below is testable by calling one method with a fixed {@link Clock} and no
 * scheduler in the picture. This is the same split {@code OrderExpirySweeper} /
 * {@code ExpireOrdersService} already uses.
 *
 * <h2>MAPPING HAPPENS INSIDE THE TRY, AND THAT IS THE MOST DELIBERATE LINE IN THE CLASS</h2>
 *
 * Open item 2 in {@code docs/project-status.md} describes the bug both existing sweeps have: they map
 * every candidate row through the aggregate inside the repository call -- OUTSIDE the per-item
 * try/catch -- so one unmappable row throws out of the whole pass, and because the ordering is
 * oldest-first it sits at the head of every batch and disables the job permanently and silently.
 * <p>
 * {@link OutboxReader#findUnpublished} therefore returns raw {@link UnpublishedEvent} rows with no
 * validation, and {@link UnpublishedEvent#toEvent()} is called below inside the try. A corrupt row
 * costs one iteration; the rest of the batch still drains.
 *
 * <h2>Ordering, and the one thing it costs</h2>
 *
 * Events come back {@code occurred_at} ascending and are dispatched one at a time, so two events for
 * one aggregate reach a consumer in the order they happened. <b>A failed event blocks its own
 * aggregate for the rest of the pass</b> ({@link #poisonedAggregates}) -- otherwise the very first
 * failure would silently deliver an aggregate's later events before its earlier one, which is worse
 * than not delivering them at all.
 * <p>
 * The residue, stated rather than discovered later: an event that fails FOREVER freezes its
 * aggregate's later events forever. It is retried at the head of every pass, fails again, and its
 * successors are skipped again. The platform still drains -- every other aggregate is unaffected --
 * but that one is stuck, visible only as a WARN per pass and as a growing
 * {@code min(occurred_at) where published_at is null}. There is no dead-letter table, no attempt
 * counter and no alert; the alert is SDD 24's "oldest unpublished event age" and belongs with
 * observability, which does not exist yet.
 *
 * <h2>Transactions</h2>
 *
 * This class opens exactly one, around the {@code published_at} stamp. The dispatcher opens its own
 * per handler, and they are deliberately not merged: see {@link EventDispatcher}. The stamp
 * committing separately from the handlers is what makes delivery at-least-once, and the inbox is
 * what makes at-least-once safe.
 */
public final class PublishOutboxEventsService {

    private static final Logger log = LoggerFactory.getLogger(PublishOutboxEventsService.class);

    private final OutboxReader reader;
    private final EventDispatcher dispatcher;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    public PublishOutboxEventsService(
        OutboxReader reader,
        EventDispatcher dispatcher,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox relay batch size must be at least 1");
        }

        this.reader = reader;
        this.dispatcher = dispatcher;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass. Returns what it did, so the timer can log it and a test can assert it without
     * reading the database.
     */
    public RelayResult publish() {
        List<UnpublishedEvent> backlog = reader.findUnpublished(batchSize);

        // The aggregates that failed in THIS pass. Their later events wait rather than overtaking
        // the one that failed -- see the class javadoc. Reset every pass, so a transient failure
        // costs one pass of latency and nothing more.
        Set<String> poisonedAggregates = new HashSet<>();

        int published = 0;
        int failed = 0;
        int deferred = 0;

        for (UnpublishedEvent row : backlog) {
            if (poisonedAggregates.contains(row.aggregateId())) {
                deferred++;
                continue;
            }

            try {
                // INSIDE THE TRY. A row that cannot form a legal envelope fails here, alone.
                OutboxEvent event = row.toEvent();

                dispatcher.dispatch(event);

                // AFTER every handler has committed, and in its own transaction. If this fails the
                // event is redelivered and each consumer's inbox row makes that a no-op.
                Instant now = Instant.now(clock);
                transactions.execute(status -> {
                    reader.markPublished(event.eventId(), now);
                    return null;
                });

                published++;
            } catch (RuntimeException failure) {
                failed++;
                poisonedAggregates.add(row.aggregateId());

                log.warn(
                    "Could not publish outbox event eventId={} eventType={} aggregateId={}",
                    row.eventId(), row.eventType(), row.aggregateId(), failure
                );
            }
        }

        return new RelayResult(backlog.size(), published, failed, deferred);
    }

    /**
     * What one pass did.
     *
     * @param examined  how many rows the claim query returned
     * @param published how many were delivered and stamped
     * @param failed    how many threw and were logged. Non-zero here is worth an alert; the events
     *                  are retried on the next pass
     * @param deferred  how many were skipped because an earlier event of the SAME aggregate failed
     *                  in this pass. Not an error -- it is the ordering guarantee doing its job --
     *                  but a number that stays non-zero across passes means an aggregate is stuck
     */
    public record RelayResult(int examined, int published, int failed, int deferred) {
    }
}
