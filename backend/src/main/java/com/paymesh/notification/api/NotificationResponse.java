package com.paymesh.notification.api;

import com.paymesh.notification.domain.Notification;

import java.time.Instant;

/**
 * What {@code GET /internal/v1/notifications/{id}} returns. A built-from-domain record (ADR); the
 * aggregate is never serialized directly.
 */
public record NotificationResponse(
    String id,
    String merchantId,
    String sourceEventId,
    String eventType,
    String subject,
    String body,
    String status,
    int attemptCount,
    String lastError,
    Instant createdAt,
    Instant updatedAt,
    Instant sentAt
) {

    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
            notification.id().value(),
            notification.merchantId().value(),
            notification.sourceEventId(),
            notification.eventType(),
            notification.subject(),
            notification.body(),
            notification.status().name(),
            notification.attemptCount(),
            notification.lastError(),
            notification.createdAt(),
            notification.updatedAt(),
            notification.sentAt()
        );
    }
}
