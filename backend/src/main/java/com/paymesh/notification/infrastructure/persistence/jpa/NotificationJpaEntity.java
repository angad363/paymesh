package com.paymesh.notification.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row of {@code notifications}. */
@Entity
@Table(name = "notifications")
public class NotificationJpaEntity {

    @Id
    @Column(name = "notification_id", nullable = false, updatable = false, length = 40)
    private String notificationId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "source_event_id", nullable = false, updatable = false, length = 40)
    private String sourceEventId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "subject", nullable = false, updatable = false, length = 256)
    private String subject;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationJpaEntity() {
    }

    public NotificationJpaEntity(
        String notificationId,
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
        this.notificationId = notificationId;
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

    public String notificationId() {
        return notificationId;
    }

    public String merchantId() {
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

    public String status() {
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
}
