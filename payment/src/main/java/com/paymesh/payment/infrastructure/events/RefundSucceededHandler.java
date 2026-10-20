package com.paymesh.payment.infrastructure.events;

import com.paymesh.payment.application.ApplyRefundSucceededService;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/**
 * Payment's consumer of {@code refund.succeeded}.
 *
 * <h2>PAYMENT IS NOW ON BOTH SIDES OF THE EVENT BUS, AND STILL IMPORTS NOTHING NEW</h2>
 *
 * It produces {@code payment.succeeded}, which Order and the Ledger consume; it now consumes
 * {@code refund.succeeded}, which Refund produces. The arrows do not form a cycle in the import
 * graph because neither direction is an import -- both are a {@code Map} read out of an envelope.
 * {@code ModuleBoundaryTest} keeps Payment's allowlist against Refund empty.
 * <p>
 * That is exactly the property ADR-016 was built for and it is being collected here for the second
 * time: a capability learns a fact from another capability without either one naming the other.
 */
public final class RefundSucceededHandler implements EventHandler {

    /** A STABLE NAME. Renaming it re-applies every refund this payment module has ever seen. */
    private static final String CONSUMER_NAME = "payment.refund-succeeded";

    private static final String EVENT_TYPE = "refund.succeeded";

    private final ApplyRefundSucceededService applyRefundSucceeded;

    public RefundSucceededHandler(ApplyRefundSucceededService applyRefundSucceeded) {
        this.applyRefundSucceeded = applyRefundSucceeded;
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

        applyRefundSucceeded.apply(
            // FROM THE ENVELOPE, not the payload. The producer copied it off the aggregate; a
            // payload field is data, and on the money path that difference decides whose payment
            // gets marked refunded.
            event.merchantId(),
            PaymentIntentId.from(requireText(payload, "paymentIntentId")),
            requireAmount(payload),
            occurredAt(payload, event)
        );
    }

    private static Instant occurredAt(Map<String, Object> payload, OutboxEvent event) {
        Object value = payload.get("occurredAt");

        return value == null ? event.occurredAt() : Instant.parse(value.toString());
    }

    /** Through {@link Number}: JSONB hands back an Integer for anything that fits in 32 bits. */
    private static long requireAmount(Map<String, Object> payload) {
        Object value = payload.get("amountMinor");

        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("refund.succeeded carries no numeric amountMinor");
        }

        return number.longValue();
    }

    private static String requireText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);

        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("refund.succeeded carries no " + key);
        }

        return value.toString();
    }
}
