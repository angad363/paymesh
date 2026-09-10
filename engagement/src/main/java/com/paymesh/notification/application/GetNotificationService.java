package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;
import com.paymesh.notification.domain.NotificationId;

/**
 * Reads one notification for support diagnosis. Platform-scoped: the caller is a platform admin
 * (see {@code NotificationController}), so this is not merchant-scoped -- support looks across
 * tenants by design, and the notification id is the only key.
 */
public final class GetNotificationService {

    private final NotificationRepository notifications;

    public GetNotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    public Notification get(NotificationId id) {
        return notifications.findById(id)
            .orElseThrow(() -> new NotificationNotFoundException(id.value()));
    }
}
