package com.paymesh.shared.tenant;

import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Feeds the {@code merchant_ref} projection from the merchant's lifecycle events (ADR-039). Four
 * beans, one class -- the four event types differ only in a string -- the same shape as
 * {@code NotificationEventHandler}.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> {@link MerchantRefStore#upsert} runs inside the
 *       dispatcher's transaction, so every schema copy and the inbox row commit together.</li>
 *   <li><b>Idempotent anyway.</b> The upsert is {@code ON CONFLICT DO UPDATE} guarded on
 *       {@code updated_at}: a redelivered or out-of-order event is a safe no-op, not a corruption.</li>
 *   <li><b>Throws to retry.</b> A payload with no status throws, leaving {@code published_at} null so
 *       the relay retries -- and, since the payload stays malformed, eventually dead-letters it
 *       (ADR-025), the correct end for an event that cannot be applied.</li>
 * </ol>
 *
 * <p>The merchant id comes from the ENVELOPE, not the payload -- the rule every consumer follows: the
 * envelope's merchant was set by the producer from the aggregate; a payload field is data.
 */
public final class MerchantRefProjector implements EventHandler {

    private final String eventType;
    private final String consumerName;
    private final MerchantRefStore store;

    /**
     * @param eventType one of {@code merchant.registered/activated/suspended/closed}; also the tail
     *     of the consumer name, so the inbox key is {@code merchant-ref.merchant.activated}. STABLE:
     *     renaming it re-opens the entire backlog to this consumer, re-projecting every event.
     */
    public MerchantRefProjector(String eventType, MerchantRefStore store) {
        this.eventType = eventType;
        this.consumerName = "merchant-ref." + eventType;
        this.store = store;
    }

    @Override
    public String consumerName() {
        return consumerName;
    }

    @Override
    public String eventType() {
        return eventType;
    }

    @Override
    public void handle(OutboxEvent event) {
        Object status = event.payload().get("status");

        if (status == null) {
            throw new IllegalStateException(
                "Merchant lifecycle event " + event.eventType() + " (" + event.eventId().value()
                    + ") carries no status; cannot project"
            );
        }

        store.upsert(event.merchantId(), status.toString(), event.occurredAt());
    }
}
