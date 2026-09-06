package com.paymesh.webhook.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data access to webhook_events. Not referenced outside this package. */
public interface SpringDataWebhookEventRepository
    extends JpaRepository<WebhookEventJpaEntity, String> {

    Optional<WebhookEventJpaEntity> findBySourceEventId(String sourceEventId);
}
