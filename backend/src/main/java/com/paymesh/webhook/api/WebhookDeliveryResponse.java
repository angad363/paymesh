package com.paymesh.webhook.api;

import com.paymesh.webhook.domain.WebhookDelivery;

import java.time.Instant;

/** What happened to one delivery, in the terms a merchant debugging it would ask in. */
public record WebhookDeliveryResponse(
    String id,
    String webhookEventId,
    String endpointId,
    String status,
    int attempts,
    Instant nextAttemptAt,
    Integer lastStatusCode,
    String lastResponseExcerpt,
    Instant createdAt,
    Instant updatedAt
) {

    public static WebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(
            delivery.deliveryId().value(),
            delivery.webhookEventId().value(),
            delivery.endpointId().value(),
            delivery.status().name(),
            delivery.attempts(),
            delivery.nextAttemptAt(),
            delivery.lastStatusCode(),
            delivery.lastResponseExcerpt(),
            delivery.createdAt(),
            delivery.updatedAt()
        );
    }
}
