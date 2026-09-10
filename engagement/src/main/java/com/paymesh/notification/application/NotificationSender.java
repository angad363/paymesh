package com.paymesh.notification.application;

import com.paymesh.notification.domain.Notification;

/**
 * The seam between "a notification is due" and "it left the building". One implementation today,
 * {@code SimulatedNotificationSender}, which always accepts -- PayMesh sends no real mail. A real
 * email/SMS provider, or a failure profile, plugs in here without the dispatcher changing (ADR-033).
 */
public interface NotificationSender {

    SendResult send(Notification notification);

    /**
     * @param delivered whether the notification was delivered
     * @param error a short failure description when {@code delivered} is false, otherwise null
     */
    record SendResult(boolean delivered, String error) {

        public static SendResult accepted() {
            return new SendResult(true, null);
        }

        public static SendResult refused(String error) {
            return new SendResult(false, error);
        }
    }
}
