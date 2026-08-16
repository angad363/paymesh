package com.paymesh.notification.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One thing a merchant is told, and the record that it was (or was not) delivered.
 *
 * <p>Immutable through intent methods, never setters (java-coding-conventions §): {@link #send} and
 * {@link #attemptFailed} return a new instance rather than mutating this one, so an aggregate can be
 * read, reasoned about, and saved without a hidden write in between.
 *
 * <h2>subject and body are rendered ONCE, at record time</h2>
 *
 * They are stored, not re-derived on read, so editing a template in {@code NotificationTemplates}
 * cannot retroactively change what a merchant was already told. The same instinct as Webhook
 * serializing its payload once into the bytes it will sign.
 */
public final class Notification {

    private final NotificationId id;
    private final MerchantId merchantId;
    private final String sourceEventId;
    private final String eventType;
    private final String subject;
    private final String body;
    private final NotificationStatus status;
    private final int attemptCount;
    private final String lastError;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant sentAt;

    private Notification(
        NotificationId id,
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        String subject,
        String body,
        NotificationStatus status,
        int attemptCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt
    ) {
        this.id = id;
        this.merchantId = merchantId;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.attemptCount = attemptCount;
        this.lastError = lastError;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.sentAt = sentAt;
    }

    /** A fresh, unsent notification. */
    public static Notification record(
        NotificationId id,
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        String subject,
        String body,
        Instant now
    ) {
        requireText(sourceEventId, "source event id");
        requireText(eventType, "event type");
        requireText(subject, "subject");
        requireText(body, "body");

        return new Notification(
            id, merchantId, sourceEventId, eventType, subject, body,
            NotificationStatus.PENDING, 0, null, now, now, null
        );
    }

    /** Rehydrates a row from the database. No validation: the row was valid when it was written. */
    public static Notification reconstitute(
        NotificationId id,
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        String subject,
        String body,
        NotificationStatus status,
        int attemptCount,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant sentAt
    ) {
        return new Notification(
            id, merchantId, sourceEventId, eventType, subject, body,
            status, attemptCount, lastError, createdAt, updatedAt, sentAt
        );
    }

    /** Delivered. Records the attempt that succeeded and clears any earlier error. */
    public Notification send(Instant now) {
        return new Notification(
            id, merchantId, sourceEventId, eventType, subject, body,
            NotificationStatus.SENT, attemptCount + 1, null, createdAt, now, now
        );
    }

    /**
     * The sender refused or threw. Records the failure and either leaves the notification PENDING to
     * be retried next pass, or moves it to FAILED once {@code maxAttempts} attempts have been spent.
     *
     * @param maxAttempts the attempt budget; reaching it is terminal, exactly as ADR-025's relay
     *     budget is terminal rather than an infinite retry
     */
    public Notification attemptFailed(String error, int maxAttempts, Instant now) {
        int attempts = attemptCount + 1;

        NotificationStatus next =
            attempts >= maxAttempts ? NotificationStatus.FAILED : NotificationStatus.PENDING;

        return new Notification(
            id, merchantId, sourceEventId, eventType, subject, body,
            next, attempts, error, createdAt, now, null
        );
    }

    public NotificationId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public String eventType() {
        return eventType;
    }

    public String subject() {
        return subject;
    }

    public String body() {
        return body;
    }

    public NotificationStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public String lastError() {
        return lastError;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant sentAt() {
        return sentAt;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Notification " + field + " cannot be blank");
        }
    }
}
