package com.paymesh.notification.infrastructure.events;

import com.paymesh.notification.application.RecordNotificationService;
import com.paymesh.notification.infrastructure.events.NotificationTemplates.Rendered;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/**
 * Notification's consumer of one event type. Three beans, one class, because the three differ only
 * in a string -- the same shape as {@code WebhookFanOutHandler}.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> Neither this nor {@link RecordNotificationService} opens
 *       one; both run inside the dispatcher's, alongside the inbox row.</li>
 *   <li><b>Idempotent anyway.</b> A different outbox event describing the same fact still lands on
 *       {@code uq_notifications_source_event}; the service's {@code saveIfAbsent} checks first.</li>
 *   <li><b>Throws to retry.</b> Nothing here is caught. A template that fails on a malformed payload
 *       leaves {@code published_at} null and the relay retries -- and, since the payload is still
 *       malformed, eventually dead-letters it (ADR-025), the correct end for an event that cannot be
 *       rendered.</li>
 * </ol>
 *
 * <p>The merchant comes from the ENVELOPE, not the payload -- the same rule the Ledger's handler
 * states: the envelope's merchant was copied from the aggregate by the producer, a payload field is
 * data.
 */
public final class NotificationEventHandler implements EventHandler {

    private final String eventType;
    private final String consumerName;
    private final NotificationTemplates templates;
    private final RecordNotificationService record;

    /**
     * @param eventType also the tail of the consumer name, so the inbox key is
     *     {@code notification.payment.succeeded}. STABLE: renaming it re-opens the entire backlog to
     *     this consumer, re-notifying every merchant of everything.
     */
    public NotificationEventHandler(
        String eventType,
        NotificationTemplates templates,
        RecordNotificationService record
    ) {
        if (!NotificationTemplates.SUBSCRIBED_TYPES.contains(eventType)) {
            throw new IllegalArgumentException(
                "No notification template is defined for " + eventType
                    + "; a handler for it would fail to render every event"
            );
        }

        this.eventType = eventType;
        this.consumerName = "notification." + eventType;
        this.templates = templates;
        this.record = record;
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
        Rendered rendered = templates.render(event.eventType(), event.payload());

        record.record(
            event.merchantId(),
            event.eventId().value(),
            event.eventType(),
            rendered.subject(),
            rendered.body()
        );
    }
}
