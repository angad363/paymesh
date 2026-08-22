package com.paymesh.audit.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A row of {@code audit_events}. Every column is {@code updatable = false}: the row is append-only,
 * the V36 trigger refuses an UPDATE, and telling Hibernate the same keeps it from ever generating
 * one.
 */
@Entity
@Table(name = "audit_events")
public class AuditEventJpaEntity {

    @Id
    @Column(name = "audit_event_id", nullable = false, updatable = false, length = 40)
    private String auditEventId;

    @Column(name = "actor_type", nullable = false, updatable = false, length = 16)
    private String actorType;

    @Column(name = "actor_id", updatable = false, length = 64)
    private String actorId;

    @Column(name = "merchant_id", updatable = false, length = 40)
    private String merchantId;

    @Column(name = "action", nullable = false, updatable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, updatable = false, length = 64)
    private String resourceType;

    @Column(name = "resource_id", updatable = false, length = 64)
    private String resourceId;

    @Column(name = "reason", updatable = false)
    private String reason;

    @Column(name = "before_hash", updatable = false, length = 64)
    private String beforeHash;

    @Column(name = "after_hash", updatable = false, length = 64)
    private String afterHash;

    @Column(name = "ip_hash", updatable = false, length = 64)
    private String ipHash;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected AuditEventJpaEntity() {
    }

    public AuditEventJpaEntity(
        String auditEventId,
        String actorType,
        String actorId,
        String merchantId,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String beforeHash,
        String afterHash,
        String ipHash,
        Instant occurredAt
    ) {
        this.auditEventId = auditEventId;
        this.actorType = actorType;
        this.actorId = actorId;
        this.merchantId = merchantId;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.reason = reason;
        this.beforeHash = beforeHash;
        this.afterHash = afterHash;
        this.ipHash = ipHash;
        this.occurredAt = occurredAt;
    }

    public String getAuditEventId() {
        return auditEventId;
    }

    public String getActorType() {
        return actorType;
    }

    public String getActorId() {
        return actorId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getReason() {
        return reason;
    }

    public String getBeforeHash() {
        return beforeHash;
    }

    public String getAfterHash() {
        return afterHash;
    }

    public String getIpHash() {
        return ipHash;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
