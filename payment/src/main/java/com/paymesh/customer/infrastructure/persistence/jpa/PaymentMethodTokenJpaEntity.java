package com.paymesh.customer.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code payment_method_tokens} (V3, V6, V19) -- and this is its first mapping. */
@Entity
@Table(name = "payment_method_tokens")
public class PaymentMethodTokenJpaEntity {

    @Id
    @Column(name = "payment_method_token_id", nullable = false, length = 40)
    private String paymentMethodTokenId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "customer_id", nullable = false, length = 40)
    private String customerId;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider;

    @Column(name = "provider_token", nullable = false, length = 255)
    private String providerToken;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "fingerprint", nullable = false, length = 64)
    private String fingerprint;

    @Column(name = "brand", length = 32)
    private String brand;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "last_four", length = 4)
    private String lastFour;

    @Column(name = "expiry_month")
    private Short expiryMonth;

    @Column(name = "expiry_year")
    private Short expiryYear;

    @Column(name = "detached_at")
    private Instant detachedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PaymentMethodTokenJpaEntity() {
    }

    public PaymentMethodTokenJpaEntity(
        String paymentMethodTokenId, String merchantId, String customerId, String provider,
        String providerToken, String fingerprint, String brand, String lastFour,
        Short expiryMonth, Short expiryYear, Instant detachedAt, Instant createdAt
    ) {
        this.paymentMethodTokenId = paymentMethodTokenId;
        this.merchantId = merchantId;
        this.customerId = customerId;
        this.provider = provider;
        this.providerToken = providerToken;
        this.fingerprint = fingerprint;
        this.brand = brand;
        this.lastFour = lastFour;
        this.expiryMonth = expiryMonth;
        this.expiryYear = expiryYear;
        this.detachedAt = detachedAt;
        this.createdAt = createdAt;
    }

    public String paymentMethodTokenId() {
        return paymentMethodTokenId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String customerId() {
        return customerId;
    }

    public String provider() {
        return provider;
    }

    public String providerToken() {
        return providerToken;
    }

    public String fingerprint() {
        return fingerprint;
    }

    public String brand() {
        return brand;
    }

    public String lastFour() {
        return lastFour;
    }

    public Short expiryMonth() {
        return expiryMonth;
    }

    public Short expiryYear() {
        return expiryYear;
    }

    public Instant detachedAt() {
        return detachedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
