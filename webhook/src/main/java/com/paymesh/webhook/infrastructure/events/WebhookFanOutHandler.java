package com.paymesh.webhook.infrastructure.events;

import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.webhook.application.FanOutWebhookEventService;
import com.paymesh.webhook.domain.WebhookEventId;

/**
 * Webhook's consumer of one event type. Four beans, one class, because the four differ only in a
 * string -- four near-identical classes would be four places to fix one bug.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> Neither this nor {@link FanOutWebhookEventService} opens
 *       one; both run inside the dispatcher's, alongside the inbox row.</li>
 *   <li><b>Idempotent anyway.</b> A different outbox event describing the same fact still lands on
 *       {@code uq_webhook_events_source} and {@code uq_webhook_deliveries_event_endpoint}; the
 *       service checks both before writing.</li>
 *   <li><b>Throws to retry.</b> Nothing here is caught. A translation that fails on a malformed
 *       payload leaves {@code published_at} null and the relay tries again -- and, since the payload
 *       will still be malformed, eventually dead-letters it (ADR-025), which is the correct end for
 *       an event that cannot be expressed on the wire.</li>
 * </ol>
 */
public final class WebhookFanOutHandler implements EventHandler {

    private final String eventType;
    private final String consumerName;
    private final WebhookPayloadTranslator translator;
    private final FanOutWebhookEventService fanOut;

    /**
     * @param eventType also the tail of the consumer name, so the inbox key is
     *     {@code webhook.payment.succeeded}. STABLE: renaming it replays every event webhook has
     *     ever fanned out, which means re-POSTing them all to every merchant.
     */
    public WebhookFanOutHandler(
        String eventType,
        WebhookPayloadTranslator translator,
        FanOutWebhookEventService fanOut
    ) {
        if (!WebhookPayloadTranslator.PUBLISHED_TYPES.contains(eventType)) {
            throw new IllegalArgumentException(
                "No webhook translation is defined for " + eventType
                    + "; a handler for it would fan out an untranslatable event"
            );
        }

        this.eventType = eventType;
        this.consumerName = "webhook." + eventType;
        this.translator = translator;
        this.fanOut = fanOut;
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
        WebhookEventId webhookEventId = WebhookEventId.generate();

        fanOut.fanOut(
            webhookEventId,
            event.merchantId(),
            event.eventId().value(),
            event.eventType(),
            // Deferred: an event no endpoint subscribes to must not be able to fail translation.
            () -> translator.translate(webhookEventId, event),
            event.occurredAt()
        );
    }
}
