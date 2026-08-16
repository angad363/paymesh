package com.paymesh.notification.domain;

/**
 * A notification's lifecycle. {@code PENDING} until the dispatcher sends it, then {@code SENT}, or
 * {@code FAILED} once the attempt budget is spent. Mirrors {@code ck_notifications_status}.
 */
public enum NotificationStatus {
    PENDING,
    SENT,
    FAILED
}
