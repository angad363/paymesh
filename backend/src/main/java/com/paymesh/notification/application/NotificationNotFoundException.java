package com.paymesh.notification.application;

/** No notification with that id. HTTP-agnostic; the API layer maps it to 404. */
public final class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String notificationId) {
        super("No notification with id " + notificationId);
    }
}
