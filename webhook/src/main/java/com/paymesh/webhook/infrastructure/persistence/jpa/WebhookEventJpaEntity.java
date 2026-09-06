package com.paymesh.webhook.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistence model for webhook_events (V24).
 *
 * <p>{@code payload} is a {@code String} mapped to {@code TEXT}, not a jsonb {@code Map}, and that
 * is the whole point: the merchant's HMAC covers the bytes they received, so a replay must resend
 * the same bytes rather than equivalent JSON. Anything that parses and re-serializes breaks the
 * signature. ADR-028 §3.1. The row is also protected by {@code tr_webhook_events_immutable}, so an
 * accidental update fails loudly at the database rather than quietly on a merchant's verifier.
 */
@Entity
@Table(name = "webhook_events")
public class WebhookEventJpaEntity {

    @Id
    @Column(name = "webhook_event_id", nullable = false, length = 40)
    private String webhookEventId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "source_event_id", nullable = false, length = 40)
    private String sourceEventId;

    @Column(name = "event_type", nullable = false, length = 60)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "payload", nullable = false)
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected WebhookEventJpaEntity() {
        // JPA
    }

    public WebhookEventJpaEntity(
        String webhookEventId,
        String merchantId,
        String sourceEventId,
        String eventType,
        int schemaVersion,
        String payload,
        Instant occurredAt,
        Instant createdAt
    ) {
        this.webhookEventId = webhookEventId;
        this.merchantId = merchantId;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    public String getWebhookEventId() {
        return webhookEventId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
