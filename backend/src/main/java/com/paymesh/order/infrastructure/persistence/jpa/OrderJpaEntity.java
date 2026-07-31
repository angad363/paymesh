package com.paymesh.order.infrastructure.persistence.jpa;

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
 * Persistence model for the orders table (V5__create_orders.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 */
@Entity
@Table(name = "orders")
public class OrderJpaEntity {

    @Id
    @Column(name = "order_id", nullable = false, length = 40)
    private String orderId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "customer_id", length = 40)
    private String customerId;

    @Column(name = "merchant_order_ref", length = 100)
    private String merchantOrderReference;

    // Minor units, never a decimal. long, not BigDecimal: the value IS a count.
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    // CHAR(3) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width column must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "amount_paid_minor", nullable = false)
    private long amountPaidMinor;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "description", length = 500)
    private String description;

    // JSONB. Hibernate serializes the map with the application's JSON mapper, so a value that
    // cannot round-trip fails on write rather than on some later read.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, String> metadata;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "cancellation_reason", length = 200)
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /**
     * Optimistic lock (SDD 23.3). Hibernate increments it on every update and refuses one whose
     * version has moved on, so two concurrent writers cannot both act on the same PENDING order.
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
    protected OrderJpaEntity() {
    }

    public OrderJpaEntity(
        String orderId,
        String merchantId,
        String customerId,
        String merchantOrderReference,
        long amountMinor,
        String currency,
        long amountPaidMinor,
        String status,
        String description,
        Map<String, String> metadata,
        Instant expiresAt,
        String cancellationReason,
        Instant cancelledAt,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.orderId = orderId;
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.merchantOrderReference = merchantOrderReference;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.amountPaidMinor = amountPaidMinor;
        this.status = status;
        this.description = description;
        this.metadata = metadata;
        this.expiresAt = expiresAt;
        this.cancellationReason = cancellationReason;
        this.cancelledAt = cancelledAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String orderId() {
        return orderId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String customerId() {
        return customerId;
    }

    public String merchantOrderReference() {
        return merchantOrderReference;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public long amountPaidMinor() {
        return amountPaidMinor;
    }

    public String status() {
        return status;
    }

    public String description() {
        return description;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public String cancellationReason() {
        return cancellationReason;
    }

    public Instant cancelledAt() {
        return cancelledAt;
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
