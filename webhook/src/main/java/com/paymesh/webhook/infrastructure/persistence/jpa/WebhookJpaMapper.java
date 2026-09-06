package com.paymesh.webhook.infrastructure.persistence.jpa;

import com.paymesh.webhook.domain.DeliveryStatus;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.EndpointStatus;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Domain ⇄ entity for all three webhook tables.
 *
 * <p>One class rather than three, because every method is a field-for-field copy and three files of
 * that is three places to notice a column was added.
 *
 * <p>{@code subscriptions} crosses as {@code Set} in the domain and {@code List} in the entity: the
 * domain wants "no duplicates", jsonb stores an array. {@link LinkedHashSet} on the way back keeps
 * the stored order, so a response lists them the way the merchant sent them.
 */
public final class WebhookJpaMapper {

    private WebhookJpaMapper() {
    }

    public static WebhookEndpointJpaEntity toEntity(WebhookEndpoint endpoint) {
        return new WebhookEndpointJpaEntity(
            endpoint.endpointId().value(),
            endpoint.merchantId(),
            endpoint.url(),
            endpoint.secretVersion(),
            endpoint.previousSecretVersion(),
            endpoint.previousSecretExpiresAt(),
            List.copyOf(endpoint.subscriptions()),
            endpoint.status().name(),
            endpoint.consecutiveFailures(),
            endpoint.version(),
            endpoint.createdAt(),
            endpoint.updatedAt()
        );
    }

    public static WebhookEndpoint toDomain(WebhookEndpointJpaEntity entity) {
        return WebhookEndpoint.rehydrate(
            EndpointId.from(entity.getEndpointId()),
            entity.getMerchantId(),
            entity.getUrl(),
            entity.getSecretVersion(),
            entity.getPreviousSecretVersion(),
            entity.getPreviousSecretExpiresAt(),
            new LinkedHashSet<>(entity.getSubscriptions()),
            EndpointStatus.valueOf(entity.getStatus()),
            entity.getConsecutiveFailures(),
            entity.getVersion(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public static WebhookEventJpaEntity toEntity(WebhookEvent event) {
        return new WebhookEventJpaEntity(
            event.webhookEventId().value(),
            event.merchantId(),
            event.sourceEventId(),
            event.eventType(),
            event.schemaVersion(),
            event.payload(),
            event.occurredAt(),
            event.createdAt()
        );
    }

    public static WebhookEvent toDomain(WebhookEventJpaEntity entity) {
        return WebhookEvent.rehydrate(
            WebhookEventId.from(entity.getWebhookEventId()),
            entity.getMerchantId(),
            entity.getSourceEventId(),
            entity.getEventType(),
            entity.getSchemaVersion(),
            entity.getPayload(),
            entity.getOccurredAt(),
            entity.getCreatedAt()
        );
    }

    public static WebhookDeliveryJpaEntity toEntity(WebhookDelivery delivery) {
        return new WebhookDeliveryJpaEntity(
            delivery.deliveryId().value(),
            delivery.webhookEventId().value(),
            delivery.endpointId().value(),
            delivery.merchantId(),
            delivery.status().name(),
            delivery.attempts(),
            delivery.nextAttemptAt(),
            delivery.lastStatusCode(),
            delivery.lastResponseExcerpt(),
            delivery.createdAt(),
            delivery.updatedAt()
        );
    }

    public static WebhookDelivery toDomain(WebhookDeliveryJpaEntity entity) {
        return WebhookDelivery.rehydrate(
            WebhookDeliveryId.from(entity.getDeliveryId()),
            WebhookEventId.from(entity.getWebhookEventId()),
            EndpointId.from(entity.getEndpointId()),
            entity.getMerchantId(),
            DeliveryStatus.valueOf(entity.getStatus()),
            entity.getAttempts(),
            entity.getNextAttemptAt(),
            entity.getLastStatusCode(),
            entity.getLastResponseExcerpt(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
