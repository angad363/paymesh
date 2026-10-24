package com.paymesh.ledger.infrastructure.events;

import com.paymesh.ledger.application.PostPaymentCapturedService;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/**
 * The Ledger's consumer of {@code payment.succeeded}. The second consumer this platform has, and
 * the one the outbox was built for.
 *
 * <h2>TWO CONSUMERS OF ONE EVENT, AND NEITHER KNOWS ABOUT THE OTHER</h2>
 *
 * Order already consumes this event to move {@code orders.status} to PAID. This one posts the
 * journal. They share nothing: different {@link #consumerName()}, so different rows in
 * {@code processed_events} and independent deduplication -- Order having handled an event must not
 * suppress the Ledger's handling of it, which is exactly why V14 leads that table's primary key
 * with the consumer name rather than the event id.
 * <p>
 * They also fail independently. The dispatcher opens a transaction per handler, so a posting that
 * throws rolls back the posting and its own inbox row while leaving Order's committed work alone.
 * The event is redelivered, Order's inbox row says "already done", and only the Ledger retries.
 *
 * <h2>It reads a Map, like Order's does, and for a stronger reason</h2>
 *
 * {@code ModuleBoundaryTest.ledgerNeverImportsPayment} has an empty allowlist. So this cannot
 * import {@code PaymentIntent} or {@code PaymentIntentStatus}, and it does not want to: the Ledger
 * is the module the SDD schedules for extraction LAST and most carefully (30.1), which means it is
 * the module whose event-reading code is most certain to one day be running in a different process
 * against a different database. Reading {@code Map<String, Object>} is what that code looks like.
 * <b>The day the Ledger becomes a service, this file does not change.</b>
 *
 * <h2>The merchant comes from the envelope</h2>
 *
 * Not from the payload, even though the payload carries one. The envelope's {@code merchantId} was
 * copied from the aggregate by the producer; a payload field is data. On the money path that
 * distinction is the difference between crediting the merchant who was paid and crediting whoever
 * the payload names.
 *
 * <h2>The Number trap</h2>
 *
 * Hibernate round-trips a JSONB attribute through serialize/deserialize, so a {@code Long} written
 * as {@code capturedAmountMinor} comes back an {@code Integer} for any amount that fits in 32 bits
 * -- which is every realistic amount. A cast to {@code Long} throws {@code ClassCastException}
 * there. Reading through {@link Number} is the only correct way to get a number out of a JSON
 * payload, and Order's consumer documents the same trap.
 */
public final class PaymentSucceededLedgerHandler implements EventHandler {

    /**
     * The inbox key. A STABLE NAME, chosen once and never changed -- it is part of the primary key
     * of {@code processed_events}, so renaming it re-opens the entire backlog to this consumer and
     * <b>re-posts every payment the ledger has ever seen</b>. On this consumer that is not a replay,
     * it is a duplicated balance. Deliberately not the class name, so a refactor cannot cause it.
     * <p>
     * Even then the damage is bounded: {@code uq_ledger_transactions_idempotency} is keyed on the
     * payment rather than the event, so a full replay would be refused row by row. Belt and braces,
     * because this is the one consumer where the failure is somebody's money.
     */
    private static final String CONSUMER_NAME = "ledger.payment-succeeded";

    private static final String EVENT_TYPE = "payment.succeeded";

    private final PostPaymentCapturedService postPaymentCaptured;

    public PaymentSucceededLedgerHandler(PostPaymentCapturedService postPaymentCaptured) {
        this.postPaymentCaptured = postPaymentCaptured;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        Map<String, Object> payload = event.payload();

        postPaymentCaptured.post(
            event.merchantId(),
            requireText(payload, "paymentIntentId"),
            requireAmount(payload),
            requireText(payload, "currency"),
            occurredAt(payload, event)
        );
    }

    /**
     * The provider's clock, or the capture instant -- whichever authority decided this payment
     * succeeded. Falls back to the envelope's own {@code occurredAt}, which is when PayMesh
     * recorded the fact.
     * <p>
     * The fallback matters more here than it does for Order. This instant is written to
     * {@code ledger_transactions.occurred_at} and is what orders the financial timeline during a
     * reconciliation; the alternative to a few seconds' imprecision is refusing to post money that
     * has definitely moved, which is the worse of the two.
     */
    private static Instant occurredAt(Map<String, Object> payload, OutboxEvent event) {
        Object value = payload.get("occurredAt");

        return value == null ? event.occurredAt() : Instant.parse(value.toString());
    }

    /** Through {@link Number}, never a cast to {@code Long}. See the class javadoc. */
    private static long requireAmount(Map<String, Object> payload) {
        Object value = payload.get("capturedAmountMinor");

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                "payment.succeeded carries no numeric capturedAmountMinor"
            );
        }

        return number.longValue();
    }

    /**
     * Throws rather than defaulting. A missing currency on the money path cannot be guessed -- the
     * platform has no default currency and inventing one would post real money into the wrong
     * account. Throwing rolls back the posting and redelivers the event, so a producer bug shows up
     * as an event that will not drain, with the reason in the log, rather than as a balance in a
     * currency nobody trades in.
     */
    private static String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("payment.succeeded carries no " + key);
        }

        return value.toString();
    }
}
