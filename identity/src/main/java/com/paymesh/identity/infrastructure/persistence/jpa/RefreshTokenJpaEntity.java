package com.paymesh.identity.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Persistence model for the refresh_tokens table (V2__create_identity_tables.sql).
 * The primary key is the token's SHA-256 hash: the plaintext never reaches this
 * table, and hashing it is what makes the row findable at all.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity {

    // CHAR(64)/CHAR(36) in the migration, not VARCHAR. Hibernate maps String to
    // VARCHAR by default and schema validation compares JDBC type codes, so the
    // fixed-width columns must say so.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "family_id", nullable = false, length = 36)
    private String familyId;

    @Column(name = "user_id", nullable = false, length = 40)
    private String userId;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** NULL means the token is still live. */
    @Column(name = "revoked_at")
    private Instant revokedAt;

    /** Required by JPA. Not for application use. */
    protected RefreshTokenJpaEntity() {
    }

    public RefreshTokenJpaEntity(
        String tokenHash,
        String familyId,
        String userId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
    ) {
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public String familyId() {
        return familyId;
    }

    public String userId() {
        return userId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
