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
}
