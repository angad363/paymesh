package com.paymesh.webhook.application;

import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;

import java.util.Optional;

/** How the application reaches webhook_events. Implemented in infrastructure. */
public interface WebhookEventRepository {

    WebhookEvent save(WebhookEvent event);

    /**
     * The redelivery lookup. {@code source_event_id} is unique, so a second dispatch of one outbox
     * event finds the row already written and reuses <b>its</b> payload -- the original bytes, which
     * is the only thing a merchant's stored signature will verify against.
     */
    Optional<WebhookEvent> findBySourceEventId(String sourceEventId);

    Optional<WebhookEvent> findById(WebhookEventId webhookEventId);
}
