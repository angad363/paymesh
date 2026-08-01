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
 * Persistence model for the payment_intents table (V8__create_payment_intents.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 * <p>
 * {@code payment_method_type} was deliberately left unmapped by V8's PR and is mapped here, by the
 * PR that owns attach and therefore owns the vocabulary that goes in it. No migration was needed:
 * the column has existed since V8 precisely so that this change is a Java change.
 */
@Entity
@Table(name = "payment_intents")
public class PaymentIntentJpaEntity {

    @Id
    @Column(name = "payment_intent_id", nullable = false, length = 40)
    private String paymentIntentId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    @Column(name = "customer_id", length = 40)
    private String customerId;

    // Minor units, never a decimal. long, not BigDecimal: the value IS a count.
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    // CHAR(3) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "capture_method", nullable = false, length = 16)
    private String captureMethod;

    // Nullable, and ck_payment_intents_method_known is what says when: null is legal only before an
    // attach has happened, or on an intent cancelled before one did.
    @Column(name = "payment_method_type", length = 20)
    private String paymentMethodType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "captured_amount_minor", nullable = false)
    private long capturedAmountMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "cancellation_reason", length = 200)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "description", length = 500)
    private String description;

    // JSONB. Hibernate serializes the map with the application's JSON mapper, so a value that
    // cannot round-trip fails on write rather than on some later read.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, String> metadata;

    /**
     * Optimistic lock (SDD 23.3). Hibernate increments it on every update and refuses one whose
     * version has moved on, so two concurrent writers cannot both act on the state they read.
     * <p>
     * Boxed rather than primitive on purpose: null means "never persisted", which is also how
     * Spring Data decides to INSERT instead of MERGE.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected PaymentIntentJpaEntity() {
    }

    public PaymentIntentJpaEntity(
        String paymentIntentId,
        String merchantId,
        String orderId,
        String customerId,
        long amountMinor,
        String currency,
        String captureMethod,
        String paymentMethodType,
        String status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        String cancellationReason,
        Instant cancelledAt,
        String description,
        Map<String, String> metadata,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentIntentId = paymentIntentId;
        this.merchantId = merchantId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.captureMethod = captureMethod;
        this.paymentMethodType = paymentMethodType;
        this.status = status;
        this.capturedAmountMinor = capturedAmountMinor;
        this.refundedAmountMinor = refundedAmountMinor;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.cancellationReason = cancellationReason;
        this.cancelledAt = cancelledAt;
        this.description = description;
        this.metadata = metadata;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String orderId() {
        return orderId;
    }

    public String customerId() {
        return customerId;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String captureMethod() {
        return captureMethod;
    }

    public String paymentMethodType() {
        return paymentMethodType;
    }

    public String status() {
        return status;
    }

    public long capturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long refundedAmountMinor() {
        return refundedAmountMinor;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public Instant cancelledAt() {
        return cancelledAt;
    }

    public String description() {
        return description;
    }

    public Map<String, String> metadata() {
        return metadata;
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
