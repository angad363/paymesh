package com.paymesh.webhook.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * Persistence model for webhook_endpoints (V24). Columns must match that migration --
 * {@code ddl-auto=validate} fails startup on drift.
 *
 * <h2>THERE IS NO SECRET COLUMN, AND THAT IS THE CAPABILITY'S CENTRAL DECISION</h2>
 *
 * {@code secretVersion} is an integer and the secret is derived from it by
 * {@code WebhookSecrets}. Nothing here is sensitive, so nothing here needs encrypting. ADR-028 §2.
 *
 * <h2>{@code subscriptions} IS JSONB WITH A {@code List} TYPE ARGUMENT, WHICH IS NEW HERE</h2>
 *
 * Five entities in this repo map {@code @JdbcTypeCode(SqlTypes.JSON)}, and every one of them maps a
 * {@code Map}. This is the first {@code List}. The distinction matters more than it looks: an
 * un-annotated {@code List<String>} defaults to a SQL <b>array</b> type, and the annotation is the
 * override that redirects it to jsonb. {@code WebhookEndpointPersistenceTest} exercises exactly
 * that round trip, because a mapping that fails {@code validate} fails at context startup in every
 * integration test at once rather than in one readable place.
 * <p>
 * A {@code text[]} column was the alternative and was rejected: there are no array columns in
 * V1..V25 and no {@code SqlTypes.ARRAY} in the codebase, so it would be the first thing to test
 * Hibernate's array validation. An {@code @ElementCollection} child table was the other, and costs
 * a fourth table plus the delete-all-and-recreate flush behind ADR-027's deadlock.
 *
 * <h2>{@code @Version}, FOR THE SAME REASON payment_intents HAS ONE</h2>
 *
 * Two concurrent rotations must not both read version N and both write N+1. The aggregate's
 * {@code rotateSecret} already refuses a mismatched {@code fromVersion}, but that check reads
 * before it writes -- the optimistic lock is what makes the pair atomic.
 */
@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpointJpaEntity {

    @Id
    @Column(name = "endpoint_id", nullable = false, length = 40)
    private String endpointId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "url", nullable = false, length = 2048)
    private String url;

    @Column(name = "secret_version", nullable = false)
    private int secretVersion;

    @Column(name = "previous_secret_version")
    private Integer previousSecretVersion;

    @Column(name = "previous_secret_expires_at")
    private Instant previousSecretExpiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subscriptions", nullable = false)
    private List<String> subscriptions;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpointJpaEntity() {
        // JPA
    }

    public WebhookEndpointJpaEntity(
        String endpointId,
        String merchantId,
        String url,
        int secretVersion,
        Integer previousSecretVersion,
        Instant previousSecretExpiresAt,
        List<String> subscriptions,
        String status,
        int consecutiveFailures,
        long version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.endpointId = endpointId;
        this.merchantId = merchantId;
        this.url = url;
        this.secretVersion = secretVersion;
        this.previousSecretVersion = previousSecretVersion;
        this.previousSecretExpiresAt = previousSecretExpiresAt;
        this.subscriptions = subscriptions;
        this.status = status;
        this.consecutiveFailures = consecutiveFailures;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public int getSecretVersion() {
        return secretVersion;
    }

    public Integer getPreviousSecretVersion() {
        return previousSecretVersion;
    }

    public Instant getPreviousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    public List<String> getSubscriptions() {
        return subscriptions;
    }

    public String getStatus() {
        return status;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
