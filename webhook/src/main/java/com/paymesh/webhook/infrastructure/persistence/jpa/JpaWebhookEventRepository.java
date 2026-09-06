package com.paymesh.webhook.infrastructure.persistence.jpa;

import com.paymesh.webhook.application.WebhookEventRepository;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;

import java.util.Optional;

/** PostgreSQL-backed WebhookEventRepository. */
public final class JpaWebhookEventRepository implements WebhookEventRepository {

    private final SpringDataWebhookEventRepository events;

    public JpaWebhookEventRepository(SpringDataWebhookEventRepository events) {
        this.events = events;
    }

    /**
     * No violation translation here, deliberately.
     *
     * <p>The only constraint this can trip is {@code uq_webhook_events_source}, and losing that race
     * means another dispatch of the same outbox event committed first. The right answer is to let it
     * throw: the handler's exception rolls back the inbox row, and the retry takes the
     * already-written branch. Catching it here would mean deciding what to return, and there is no
     * correct answer that does not re-read the row anyway.
     */
    @Override
    public WebhookEvent save(WebhookEvent event) {
        return WebhookJpaMapper.toDomain(events.saveAndFlush(WebhookJpaMapper.toEntity(event)));
    }

    @Override
    public Optional<WebhookEvent> findBySourceEventId(String sourceEventId) {
        return events.findBySourceEventId(sourceEventId).map(WebhookJpaMapper::toDomain);
    }

    @Override
    public Optional<WebhookEvent> findById(WebhookEventId webhookEventId) {
        return events.findById(webhookEventId.value()).map(WebhookJpaMapper::toDomain);
    }
}
