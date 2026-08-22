package com.paymesh.audit.domain;

import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * One immutable line of the audit log: who did what, to which object, when, and the hashed state
 * either side (SDD 19.3, ADR-035).
 *
 * <h2>THIS TYPE HOLDS HASHES, NEVER PLAINTEXT</h2>
 *
 * {@code beforeHash}, {@code afterHash} and {@code ipHash} are already SHA-256 by the time an
 * {@code AuditEvent} exists -- the {@code AuditRecorder} adapter hashes the plaintext from an
 * {@code AuditEntry} before it constructs this. The domain never sees a secret or an address, which
 * is what lets the whole capability be careless about where its rows travel.
 *
 * <h2>NO MUTATION METHODS, BECAUSE THE ROW IS APPEND-ONLY</h2>
 *
 * There is no {@code redact()} or {@code correct()} -- a mistaken audit entry is fixed by appending
 * a new one that says so, never by editing. The immutability trigger in V36 makes that the only
 * option the database allows; this class simply offers no other.
 */
public final class AuditEvent {

    /** Reason and hashed fields are capped so a caller cannot turn the log into a blob store. */
    public static final int MAX_REASON_LENGTH = 2000;

    private final AuditEventId id;
    private final ActorType actorType;
    private final String actorId;
    private final MerchantId merchantId;
    private final String action;
    private final String resourceType;
    private final String resourceId;
    private final String reason;
    private final String beforeHash;
    private final String afterHash;
    private final String ipHash;
    private final Instant occurredAt;

    private AuditEvent(
        AuditEventId id,
        ActorType actorType,
        String actorId,
        MerchantId merchantId,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String beforeHash,
        String afterHash,
        String ipHash,
        Instant occurredAt
    ) {
        this.id = id;
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

    /**
     * A fresh event, minted when the action commits.
     *
     * @throws IllegalArgumentException on the same actor/actor-id invariant the DB enforces, so a
     *     bad entry fails as a readable domain error rather than a constraint violation
     */
    public static AuditEvent record(
        AuditEventId id,
        ActorType actorType,
        String actorId,
        MerchantId merchantId,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String beforeHash,
        String afterHash,
        String ipHash,
        Instant occurredAt
    ) {
        if (id == null || actorType == null || action == null || action.isBlank()
            || resourceType == null || resourceType.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException(
                "An audit event needs an id, actor type, action, resource type and time"
            );
        }

        boolean hasActor = actorId != null && !actorId.isBlank();

        if (actorType == ActorType.SYSTEM && hasActor) {
            throw new IllegalArgumentException("A SYSTEM audit event carries no actor id");
        }

        if (actorType != ActorType.SYSTEM && !hasActor) {
            throw new IllegalArgumentException("A " + actorType + " audit event needs an actor id");
        }

        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                "An audit reason cannot exceed " + MAX_REASON_LENGTH + " characters"
            );
        }

        return new AuditEvent(
            id, actorType, actorId, merchantId, action, resourceType, resourceId,
            reason, beforeHash, afterHash, ipHash, occurredAt
        );
    }

    /** Rehydrates a row. No validation: it was valid when it was written and cannot change. */
    public static AuditEvent reconstitute(
        AuditEventId id,
        ActorType actorType,
        String actorId,
        MerchantId merchantId,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String beforeHash,
        String afterHash,
        String ipHash,
        Instant occurredAt
    ) {
        return new AuditEvent(
            id, actorType, actorId, merchantId, action, resourceType, resourceId,
            reason, beforeHash, afterHash, ipHash, occurredAt
        );
    }

    public AuditEventId id() {
        return id;
    }

    public ActorType actorType() {
        return actorType;
    }

    public String actorId() {
        return actorId;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public String action() {
        return action;
    }

    public String resourceType() {
        return resourceType;
    }

    public String resourceId() {
        return resourceId;
    }

    public String reason() {
        return reason;
    }

    public String beforeHash() {
        return beforeHash;
    }

    public String afterHash() {
        return afterHash;
    }

    public String ipHash() {
        return ipHash;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}
