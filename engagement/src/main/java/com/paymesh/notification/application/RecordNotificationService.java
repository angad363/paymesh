package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * Writes the PENDING notification for one consumed event. Called from the {@code EventHandler},
 * so it runs INSIDE the dispatcher's transaction and does not open one of its own -- the inbox row
 * and this write commit together (the three {@code EventHandler} rules).
 *
 * <p>Idempotent through {@link NotificationRepository#saveIfAbsent}: a redelivery of the same outbox
 * event finds the existing row and does nothing. It only ever writes; nothing here can affect the
 * payment that produced the event, which is the whole reason Notification is a consumer rather than
 * a step in any transaction (ADR-033).
 */
public final class RecordNotificationService {

    private final NotificationRepository notifications;
    private final Clock clock;

    public RecordNotificationService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    public void record(
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        String subject,
        String body
    ) {
        Notification notification = Notification.record(
            NotificationId.generate(),
            merchantId,
            sourceEventId,
            eventType,
            subject,
            body,
            Instant.now(clock)
        );

        notifications.saveIfAbsent(notification);
    }
}
