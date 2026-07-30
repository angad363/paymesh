package com.paymesh.customer.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for the customers table (V3__create_customers.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup on drift.
 */
@Entity
@Table(name = "customers")
public class CustomerJpaEntity {

    @Id
    @Column(name = "customer_id", nullable = false, length = 40)
    private String customerId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "merchant_reference", length = 100)
    private String merchantReference;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    // CHAR(64) in the migration, not VARCHAR. Hibernate maps String to VARCHAR by default and schema
    // validation compares JDBC type codes, so the fixed-width hash columns must say so.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "email_hash", nullable = false, length = 64)
    private String emailHash;

    @Column(name = "name", length = 200)
    private String name;

    @Column(name = "phone", length = 32)
    private String phone;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "phone_hash", length = 64)
    private String phoneHash;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected CustomerJpaEntity() {
    }

    public CustomerJpaEntity(
        String customerId,
        String merchantId,
        String merchantReference,
        String email,
        String emailHash,
        String name,
        String phone,
        String phoneHash,
        String status,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.merchantReference = merchantReference;
        this.email = email;
        this.emailHash = emailHash;
        this.name = name;
        this.phone = phone;
        this.phoneHash = phoneHash;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String customerId() {
        return customerId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String merchantReference() {
        return merchantReference;
    }

    public String email() {
        return email;
    }

    public String emailHash() {
        return emailHash;
    }

    public String name() {
        return name;
    }

    public String phone() {
        return phone;
    }

    public String phoneHash() {
        return phoneHash;
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
