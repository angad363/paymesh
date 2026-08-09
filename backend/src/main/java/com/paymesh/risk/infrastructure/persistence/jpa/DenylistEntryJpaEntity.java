package com.paymesh.risk.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row of {@code denylist_entries}. Mutable in principle -- an entry can be removed -- so no
 * {@code @Immutable} here, unlike its sibling. */
@Entity
@Table(name = "denylist_entries")
public class DenylistEntryJpaEntity {

    @Id
    @Column(name = "entry_id", nullable = false, updatable = false, length = 40)
    private String entryId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 20)
    private String entityType;

    @Column(name = "hashed_value", nullable = false, updatable = false, length = 64)
    private String hashedValue;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected DenylistEntryJpaEntity() {
    }

    public DenylistEntryJpaEntity(
        String entryId,
        String merchantId,
        String entityType,
        String hashedValue,
        String reason,
        Instant createdAt,
        Instant expiresAt
    ) {
        this.entryId = entryId;
        this.merchantId = merchantId;
        this.entityType = entityType;
        this.hashedValue = hashedValue;
        this.reason = reason;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String entryId() {
        return entryId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String entityType() {
        return entityType;
    }

    public String hashedValue() {
        return hashedValue;
    }

    public String reason() {
        return reason;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }
}
