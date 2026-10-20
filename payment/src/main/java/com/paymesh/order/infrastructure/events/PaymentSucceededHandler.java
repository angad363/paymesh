package com.paymesh.order.infrastructure.events;

import com.paymesh.order.application.ApplyPaymentSucceededService;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/**
 * Order's consumer of {@code payment.succeeded} (ADR-016). The first consumer this platform has.
 *
 * <h2>IT READS A MAP, AND THAT IS THE BOUNDARY DOING ITS JOB</h2>
 *
 * {@code ModuleBoundaryTest.orderNeverImportsPayment} has an EMPTY allowlist -- unlike the two
 * Payment-to-Order adapters, Order gets no exceptions at all. So this class cannot import
 * {@code PaymentIntent}, {@code PaymentIntentStatus} or anything else from
 * {@code com.paymesh.payment}, and it does not want to: it reads the payload the way a consumer in
 * another process would, out of {@code Map<String, Object>}. The awkwardness of untyped access is
 * the point rather than a cost -- <b>the day Payment becomes a service, this file does not
 * change.</b>
 * <p>
 * The merchant is taken from the ENVELOPE, not the payload. The envelope's {@code merchantId} was
 * copied from the aggregate by the producer and can never be a caller's; a payload field is data.
 *
 * <h2>The Number trap, which is real and was found in ADR-010</h2>
 *
 * Hibernate snapshots a JSONB attribute through serialize/deserialize, so a {@code Long} appended as
 * {@code capturedAmountMinor} comes back an {@code Integer}. A cast to {@code Long} would throw
 * {@code ClassCastException} on every event whose amount fits in 32 bits, which is every realistic
 * amount. Reading through {@link Number} is not defensive style; it is the only correct way to read
 * a number out of a JSON payload.
 *
 * <h2>What it does not do</h2>
 *
 * No transaction (the dispatcher owns it), no deduplication (the dispatcher's inbox owns it), and no
 * business rule ({@link ApplyPaymentSucceededService} owns those). This class unpacks an envelope
 * and makes one call, and if a decision ever appears in it, it is in the wrong file.
 */
public final class PaymentSucceededHandler implements EventHandler {

    /**
     * The inbox key. A STABLE NAME, chosen once and never changed -- it is a primary key column in
     * {@code processed_events}, so renaming it re-opens the entire backlog to this consumer and
     * replays every payment it has ever seen. Deliberately not the class name, so a refactor cannot
     * cause that.
     */
    private static final String CONSUMER_NAME = "order.payment-succeeded";

    private static final String EVENT_TYPE = "payment.succeeded";

    private final ApplyPaymentSucceededService applyPaymentSucceeded;

    public PaymentSucceededHandler(ApplyPaymentSucceededService applyPaymentSucceeded) {
        this.applyPaymentSucceeded = applyPaymentSucceeded;
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

        applyPaymentSucceeded.apply(
            event.merchantId(),
            OrderId.from(requireText(payload, "orderId")),
            requireAmount(payload),
            occurredAt(payload, event)
        );
    }

    /**
     * The provider's clock, or the capture instant -- whichever authority decided this payment
     * succeeded (ADR-016 section 6). Both emitters now carry it under this one key; before that fix
     * one of them called it {@code capturedAt} and the other omitted it, at the same envelope
     * version.
     * <p>
     * Falls back to the envelope's own {@code occurredAt}, which is when PayMesh recorded the fact.
     * They are genuinely different on a late delivery, and the payload's is the better answer -- but
     * an order that cannot be marked paid because a timestamp is missing is a worse outcome than one
     * stamped a few seconds late.
     */
    private static Instant occurredAt(Map<String, Object> payload, OutboxEvent event) {
        Object value = payload.get("occurredAt");

        return value == null ? event.occurredAt() : Instant.parse(value.toString());
    }

    /**
     * Through {@link Number}, never a cast to {@code Long}. See the class javadoc -- JSONB gives back
     * an {@code Integer} for anything that fits in one, which is every amount this platform will ever
     * see.
     */
    private static long requireAmount(Map<String, Object> payload) {
        Object value = payload.get("capturedAmountMinor");

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(
                "payment.succeeded carries no numeric capturedAmountMinor"
            );
        }

        return number.longValue();
    }

    private static String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("payment.succeeded carries no " + key);
        }

        return value.toString();
    }
}
