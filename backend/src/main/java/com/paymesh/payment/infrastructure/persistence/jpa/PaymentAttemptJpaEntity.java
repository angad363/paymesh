package com.paymesh.payment.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Persistence model for the payment_attempts table (V9__create_payment_attempts.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 * <p>
 * FIVE COLUMNS OF V9 ARE DELIBERATELY NOT MAPPED: {@code provider_reference},
 * {@code failure_code}, {@code failure_message}, {@code last_provider_event_at} and
 * {@code response_payload}. All five are written by a provider answering, and in this PR nothing
 * calls a provider, so there is nothing to put in them. An unmapped nullable column is not schema
 * drift, and the PR that owns callbacks maps them alongside the logic that fills them -- the same
 * choice V8 made with {@code payment_method_type}.
 */
@Entity
@Table(name = "payment_attempts")
public class PaymentAttemptJpaEntity {

    @Id
    @Column(name = "payment_attempt_id", nullable = false, length = 40)
    private String paymentAttemptId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "payment_intent_id", nullable = false, length = 40)
    private String paymentIntentId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    // Enum NAME, never the ordinal; the adapter converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    // Minor units, never a decimal. long, not BigDecimal: the value IS a count.
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    // CHAR(3) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // JSONB, and REDACTED before it gets here: the domain strips a return URL's query string,
    // because this row is a durable audit record and a query string routinely carries a token.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private Map<String, String> requestPayload;

    /**
     * Optimistic lock (SDD 23.3), for the updates a provider callback will make. Boxed rather than
     * primitive on purpose: null means "never persisted", which is also how Spring Data decides to
     * INSERT instead of MERGE.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected PaymentAttemptJpaEntity() {
    }

    public PaymentAttemptJpaEntity(
        String paymentAttemptId,
        String merchantId,
        String paymentIntentId,
        int attemptNumber,
        String provider,
        String status,
        long amountMinor,
        String currency,
        Map<String, String> requestPayload,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentAttemptId = paymentAttemptId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.attemptNumber = attemptNumber;
        this.provider = provider;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.requestPayload = requestPayload;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String paymentAttemptId() {
        return paymentAttemptId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public String provider() {
        return provider;
    }

    public String status() {
        return status;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public Map<String, String> requestPayload() {
        return requestPayload;
    }

    public Integer version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
