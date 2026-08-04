package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.EventId;

import java.time.Instant;
import java.util.List;

/**
 * The relay's half of the outbox: claim the backlog, then record what was delivered.
 * <p>
 * Deliberately separate from {@link OutboxWriter}. Producers append and must never be able to mark
 * something published; the relay publishes and must never be able to append. One interface carrying
 * both would let either mistake compile.
 */
public interface OutboxReader {

    /**
     * The oldest unpublished events, {@code occurred_at} ascending, at most {@code limit} of them.
     * <p>
     * THE ORDER IS PART OF THE CONTRACT, not an implementation detail: two events for one aggregate
     * must reach a consumer in the order they happened, and the relay dispatches this list
     * sequentially. An implementation that ordered by anything else would deliver a payment's
     * outcome before the payment.
     * <p>
     * Bounded so one pass cannot load an unbounded backlog into memory, and so the relay's own
     * transaction count per pass is predictable.
     * <p>
     * Returns raw rows rather than validated envelopes -- see {@link UnpublishedEvent} for the
     * reason, which is a bug this codebase already has twice elsewhere.
     */
    List<UnpublishedEvent> findUnpublished(int limit);

    /**
     * Stamps {@code published_at}, which is the entire status model (V7): NULL means unpublished and
     * there is no status column to disagree with it.
     * <p>
     * Called AFTER every handler has committed, in its own transaction. If it fails, the event is
     * simply redelivered and each consumer's inbox row makes that a no-op -- which is what
     * "at-least-once" means and is not a defect to remove.
     * <p>
     * Idempotent: an already-published event is left alone rather than re-stamped, so a second
     * relay instance cannot rewrite the first one's delivery time.
     */
    void markPublished(EventId eventId, Instant publishedAt);

    /**
     * Records one failed delivery attempt, and gives up on the attempt that reaches
     * {@code maxAttempts} (ADR-025).
     *
     * <h2>THE ID IS A RAW STRING, AND THAT IS THE WHOLE POINT</h2>
     *
     * Every other method here speaks {@link EventId}. This one cannot, because the failure it exists
     * to record includes {@link UnpublishedEvent#toEvent()} throwing -- a stored row whose event id
     * does not parse. Demanding a validated {@code EventId} would make the one class of failure that
     * can NEVER succeed on retry the one class that can never be dead-lettered, and that row would
     * block its aggregate for the lifetime of the system. So the relay passes the string it read.
     * <p>
     * A row whose id is corrupt beyond matching simply updates nothing, which is the honest outcome:
     * there is no row to give up on.
     *
     * @param error the failure's message, stored for the operator who has to explain the stall. Only
     *              the most recent one is kept.
     */
    void recordFailedAttempt(String eventId, Instant attemptedAt, String error, int maxAttempts);

    /**
     * SDD section 24's alert input: how far behind delivery is, and how much has been abandoned.
     * <p>
     * One method rather than two, because the two numbers are read together by the one caller that
     * wants them and splitting them would mean two round trips to answer one question.
     */
    BacklogHealth backlogHealth();

    /**
     * @param oldestUnpublished when the oldest STILL-DELIVERABLE unpublished event happened, or null
     *     when there is no backlog. Dead-lettered rows are excluded: their age only grows, so
     *     counting them would pin this past any threshold forever and make the signal worthless.
     * @param deadLettered how many events the relay has permanently given up on. Any non-zero value
     *     means a committed state change was never announced to its consumers.
     */
    record BacklogHealth(Instant oldestUnpublished, long deadLettered) {
    }
}
