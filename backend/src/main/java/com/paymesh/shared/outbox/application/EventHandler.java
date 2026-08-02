package com.paymesh.shared.outbox.application;

import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * What a capability implements to consume a domain event.
 *
 * <h2>This is the contract a KAFKA consumer would need, and that is the point</h2>
 *
 * Delivery is in-process and synchronous today (ADR-016) because a broker inside one process buys
 * nothing. But an in-process listener's natural contract -- a method call with typed arguments -- is
 * not the contract a broker gives you, and building to it would mean rewriting every consumer on the
 * day the transport changes. So the shape here is the broker's: <b>an event envelope in, an inbox row
 * for deduplication, an idempotent handler.</b> Swapping {@code EventDispatcher} for a Kafka listener
 * changes no implementation of this interface.
 * <p>
 * A consequence worth naming: the payload arrives as {@code Map<String, Object>}, not as a typed
 * record. That is what crosses a wire, and it is also what keeps a consumer from importing the
 * producer's domain types -- which is precisely how {@code ModuleBoundaryTest.orderNeverImportsPayment}
 * keeps its empty allowlist while Order consumes a Payment event.
 *
 * <h2>Three rules an implementation must not break</h2>
 *
 * <ol>
 *   <li><b>Do not open a transaction.</b> {@link EventDispatcher} has already opened one and the
 *       inbox row is inside it. A handler that opened its own would commit independently of that
 *       row, so a crash between the two would double-apply on redelivery -- the exact failure the
 *       inbox exists to make impossible.</li>
 *   <li><b>Be idempotent anyway.</b> The inbox stops the SAME event being applied twice. It does not
 *       stop a DIFFERENT event describing the same fact -- a reconciliation job re-announcing an
 *       outcome under a fresh id, say -- so a handler still re-checks the state it is about to
 *       change. Neither mechanism subsumes the other.</li>
 *   <li><b>Throw to retry.</b> A handler that swallows a failure tells the relay it succeeded, the
 *       inbox row commits, and the event is never redelivered. Throwing rolls back the inbox row with
 *       the work, leaves {@code published_at} null, and the next pass tries again.</li>
 * </ol>
 */
public interface EventHandler {

    /**
     * The stable name this consumer is known by in {@code processed_events}.
     * <p>
     * CHOSEN ONCE AND NEVER CHANGED. It is a primary key column: renaming it re-opens the entire
     * backlog to this consumer, which replays every event it has ever handled. Not a class name --
     * a class rename must not be able to cause that.
     */
    String consumerName();

    /** The single event type this handler subscribes to, e.g. {@code "payment.succeeded"}. */
    String eventType();

    /**
     * Applies the event. Runs inside the dispatcher's transaction, after the inbox row for this
     * {@code (consumerName, eventId)} pair has been claimed.
     */
    void handle(OutboxEvent event);
}
