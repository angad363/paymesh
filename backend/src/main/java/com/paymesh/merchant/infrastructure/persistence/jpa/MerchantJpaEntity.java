package com.paymesh.merchant.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for the merchants table (V1__create_merchants.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 */
@Entity
@Table(name = "merchants")
public class MerchantJpaEntity {

    @Id
    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "business_name", nullable = false, length = 200)
    private String businessName;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    // CHAR(2)/CHAR(3) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default,
    // and schema validation compares JDBC type codes, so the fixed-width columns must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", nullable = false, length = 2)
    private String country;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected MerchantJpaEntity() {
    }

    public MerchantJpaEntity(
        String merchantId,
        String businessName,
        String email,
        String country,
        String defaultCurrency,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.merchantId = merchantId;
        this.businessName = businessName;
        this.email = email;
        this.country = country;
        this.defaultCurrency = defaultCurrency;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String merchantId() {
        return merchantId;
    }

    public String businessName() {
        return businessName;
    }

    public String email() {
        return email;
    }

    public String country() {
        return country;
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    public String status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
