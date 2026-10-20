package com.paymesh.identity.infrastructure.persistence.jpa;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Persistence model for the users table (V2__create_identity_tables.sql).
 * Column definitions must match that migration -- ddl-auto=validate fails startup
 * on drift.
 */
@Entity
@Table(name = "users")
public class UserJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false, length = 40)
    private String userId;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    // Enum NAME, never the ordinal; the mapper converts and thereby validates it.
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    /**
     * EAGER on purpose. Roles are a handful of rows, every caller that loads a user
     * needs them (they go into the access token), and open-in-view is false -- a
     * lazy collection would fail the moment the persistence context closed.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "user_roles",
        joinColumns = @JoinColumn(
            name = "user_id",
            foreignKey = @ForeignKey(name = "fk_user_roles_user")
        )
    )
    private Set<UserRoleEmbeddable> roles = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Required by JPA. Not for application use. */
    protected UserJpaEntity() {
    }

    public UserJpaEntity(
        String userId,
        String email,
        String passwordHash,
        String status,
        Set<UserRoleEmbeddable> roles,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.userId = userId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.roles = new LinkedHashSet<>(roles);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String userId() {
        return userId;
    }

    public String email() {
        return email;
    }

    public String passwordHash() {
        return passwordHash;
    }

    public String status() {
        return status;
    }

    public Set<UserRoleEmbeddable> roles() {
        return roles;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
