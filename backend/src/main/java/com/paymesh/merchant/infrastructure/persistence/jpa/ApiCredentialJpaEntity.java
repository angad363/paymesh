package com.paymesh.merchant.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Maps {@code api_credentials} (V17). */
@Entity
@Table(name = "api_credentials")
public class ApiCredentialJpaEntity {

    @Id
    @Column(name = "api_credential_id", nullable = false, length = 40)
    private String apiCredentialId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "public_prefix", nullable = false, length = 40)
    private String publicPrefix;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "secret_hash", nullable = false, length = 64)
    private String secretHash;

    @Column(name = "role", nullable = false, length = 32)
    private String role;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApiCredentialJpaEntity() {
    }

    public ApiCredentialJpaEntity(
        String apiCredentialId,
        String merchantId,
        String publicPrefix,
        String secretHash,
        String role,
        String label,
        Instant revokedAt,
        Instant lastUsedAt,
        Instant createdAt
    ) {
        this.apiCredentialId = apiCredentialId;
        this.merchantId = merchantId;
        this.publicPrefix = publicPrefix;
        this.secretHash = secretHash;
        this.role = role;
        this.label = label;
        this.revokedAt = revokedAt;
        this.lastUsedAt = lastUsedAt;
        this.createdAt = createdAt;
    }

    public String apiCredentialId() {
        return apiCredentialId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String publicPrefix() {
        return publicPrefix;
    }

    public String secretHash() {
        return secretHash;
    }

    public String role() {
        return role;
    }

    public String label() {
        return label;
    }

    public Instant revokedAt() {
        return revokedAt;
    }

    public Instant lastUsedAt() {
        return lastUsedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
