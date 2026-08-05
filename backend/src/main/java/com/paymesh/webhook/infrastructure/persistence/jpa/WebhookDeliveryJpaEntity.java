package com.paymesh.webhook.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Persistence model for webhook_deliveries (V25).
 *
 * <p>{@code nextAttemptAt} and {@code lastStatusCode} are nullable on purpose and the database
 * agrees: {@code ck_webhook_deliveries_schedule} makes a terminal delivery with a schedule
 * unrepresentable, and a refused connection genuinely has no status code.
 *
 * <p>No {@code @Version} here, unlike the endpoint. Two dispatcher passes never work the same row:
 * the claim query takes {@code FOR UPDATE SKIP LOCKED}, which is a stronger guarantee than an
 * optimistic retry would be for a row whose write is a side effect of an HTTP call.
 */
@Entity
@Table(name = "webhook_deliveries")
public class WebhookDeliveryJpaEntity {

    @Id
    @Column(name = "delivery_id", nullable = false, length = 40)
    private String deliveryId;

    @Column(name = "webhook_event_id", nullable = false, length = 40)
    private String webhookEventId;

    @Column(name = "endpoint_id", nullable = false, length = 40)
    private String endpointId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_status_code")
    private Integer lastStatusCode;

    @Column(name = "last_response_excerpt", length = 512)
    private String lastResponseExcerpt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookDeliveryJpaEntity() {
        // JPA
    }

    public WebhookDeliveryJpaEntity(
        String deliveryId,
        String webhookEventId,
        String endpointId,
        String merchantId,
        String status,
        int attempts,
        Instant nextAttemptAt,
        Integer lastStatusCode,
        String lastResponseExcerpt,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.deliveryId = deliveryId;
        this.webhookEventId = webhookEventId;
        this.endpointId = endpointId;
        this.merchantId = merchantId;
        this.status = status;
        this.attempts = attempts;
        this.nextAttemptAt = nextAttemptAt;
        this.lastStatusCode = lastStatusCode;
        this.lastResponseExcerpt = lastResponseExcerpt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public String getWebhookEventId() {
        return webhookEventId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Integer getLastStatusCode() {
        return lastStatusCode;
    }

    public String getLastResponseExcerpt() {
        return lastResponseExcerpt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
