package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.EventId;

import java.time.Instant;

/**
 * The inbox (SDD 22.4): which consumer has already applied which event.
 * <p>
 * ONE METHOD, AND IT IS A CLAIM RATHER THAN A QUESTION. There is deliberately no
 * {@code hasProcessed(...)} returning a boolean for the caller to act on: that is a read followed by
 * a write, and two concurrent deliveries of one event would both read "no" and both handle it. The
 * insert IS the decision, and the primary key {@code (consumer_name, event_id)} is what arbitrates
 * it -- the same shape ADR-009 uses for public writes, for the same reason.
 * <p>
 * Like {@code OutboxWriter}, this assumes a transaction is already open and must not start one. The
 * claim and the work it authorizes commit together or not at all; see V14's header for what each way
 * of splitting them loses.
 */
public interface ProcessedEventRepository {

    /**
     * Claims one event for one consumer.
     *
     * @return {@code true} when this call inserted the row, meaning the caller is the one that must
     *         handle the event. {@code false} means some earlier delivery already did, and the
     *         caller must do nothing at all.
     */
    boolean markProcessed(
        String consumerName,
        EventId eventId,
        String eventType,
        Instant processedAt
    );
}
