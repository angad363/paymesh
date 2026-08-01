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
 * Every column of V9 is now mapped. The five that V9 declared and the confirm PR left unmapped --
 * {@code provider_reference}, {@code failure_code}, {@code failure_message},
 * {@code last_provider_event_at} and {@code response_payload} -- arrive here with the callback logic
 * that fills them, which was the point of deferring them.
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

    // The provider's own id for this try. Null until a callback names one, and unique per provider
    // once it does (uq_payment_attempts_provider_reference) -- it is the fallback join key a
    // callback arrives on when it does not carry a paymentIntentId.
    @Column(name = "provider_reference", length = 120)
    private String providerReference;

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

    // The provider's reason, after redaction.
    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    /**
     * THE OUT-OF-ORDER GUARD (ADR-012). The provider timestamp of the last event applied to this
     * attempt; null until one has been. Compared across all of an intent's attempts, because the
     * PROCESSING/REQUIRES_ACTION cycle spans two of them.
     */
    @Column(name = "last_provider_event_at")
    private Instant lastProviderEventAt;

    // JSONB, and REDACTED before it gets here: the domain strips a return URL's query string,
    // because this row is a durable audit record and a query string routinely carries a token.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private Map<String, String> requestPayload;

    // JSONB, redacted by allowlist before it gets here: only the fields ProviderEvent can hold
    // survive, so a provider that starts sending instrument data has nowhere to put it (SDD 12.6).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload")
    private Map<String, Object> responsePayload;

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
        String providerReference,
        String status,
        long amountMinor,
        String currency,
        String failureCode,
        String failureMessage,
        Instant lastProviderEventAt,
        Map<String, String> requestPayload,
        Map<String, Object> responsePayload,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentAttemptId = paymentAttemptId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.attemptNumber = attemptNumber;
        this.provider = provider;
        this.providerReference = providerReference;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.lastProviderEventAt = lastProviderEventAt;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
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

    public String providerReference() {
        return providerReference;
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

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant lastProviderEventAt() {
        return lastProviderEventAt;
    }

    public Map<String, String> requestPayload() {
        return requestPayload;
    }

    public Map<String, Object> responsePayload() {
        return responsePayload;
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
