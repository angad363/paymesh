package com.paymesh.ledger.infrastructure.events;

import com.paymesh.ledger.application.PostRefundReversalService;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.Map;

/**
 * The Ledger's consumer of {@code refund.succeeded}. THE REVERSAL PATH ADR-018 SAID REFUND WOULD
 * BRING.
 * <p>
 * V15 made a posted entry impossible to edit or delete, and said so in as many words: "this trigger
 * is what makes the reversal the only available option when it arrives, rather than the disciplined
 * option". This is that option arriving. Nothing here undoes the original capture journal -- it
 * cannot -- it writes a new one in the opposite direction.
 * <p>
 * Reads a {@code Map} and imports nothing from Refund, like every other consumer here.
 */
public final class RefundSucceededLedgerHandler implements EventHandler {

    /** A STABLE NAME. Renaming it replays every refund the ledger has ever reversed. */
    private static final String CONSUMER_NAME = "ledger.refund-succeeded";

    private static final String EVENT_TYPE = "refund.succeeded";

    private final PostRefundReversalService postRefundReversal;

    public RefundSucceededLedgerHandler(PostRefundReversalService postRefundReversal) {
        this.postRefundReversal = postRefundReversal;
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

        postRefundReversal.post(
            event.merchantId(),
            requireText(payload, "refundId"),
            requireText(payload, "paymentIntentId"),
            requireAmount(payload),
            requireText(payload, "currency"),
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
