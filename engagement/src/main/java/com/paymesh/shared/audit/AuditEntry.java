package com.paymesh.shared.audit;

import com.paymesh.shared.tenant.MerchantId;

/**
 * One privileged or operational action, described for the audit log by the code that performs it.
 *
 * <h2>PLAINTEXT IN, HASHED AT REST</h2>
 *
 * A caller hands over the {@code before} and {@code after} state, and the source {@code ip}, as
 * plaintext. The {@link AuditRecorder} hashes them before they touch the database, so a caller
 * cannot store a secret by forgetting to hash it -- the one place that knows how is the one place it
 * happens. See {@code audit_events} (V36) on why the log holds hashes and never values.
 *
 * <h2>OPTIONAL FIELDS ARE NULL, AND THE BUILDER MAKES THAT READABLE</h2>
 *
 * A SYSTEM action has no actor id and no IP. A creation has no {@code before}. A platform-role grant
 * targets a user, so it has no {@code merchantId}. Rather than a nine-argument constructor with a
 * row of nulls at every call site, {@link #builder(String, ActorType)} names only what an action
 * actually carries. The two required facts -- WHAT happened and WHO did it -- are the builder's
 * arguments; everything else is a fluent step.
 *
 * @param action        dotted action string, e.g. {@code merchant.suspended} (required)
 * @param actorType     USER, SYSTEM or PROVIDER (required)
 * @param actorId       the {@code usr_} for a USER, the provider name for a PROVIDER, null for SYSTEM
 * @param merchantId    the tenant acted on, or null for a platform-wide action
 * @param resourceType  the kind of object touched, e.g. {@code merchant} (required)
 * @param resourceId    the object's id, or null when the action names no single object
 * @param reason        why, when the action carries one
 * @param before        plaintext state before the change, hashed by the recorder; null on a creation
 * @param after         plaintext state after the change, hashed by the recorder; null on a deletion
 * @param ip            plaintext source address, hashed by the recorder; null below the HTTP boundary
 */
public record AuditEntry(
    String action,
    ActorType actorType,
    String actorId,
    MerchantId merchantId,
    String resourceType,
    String resourceId,
    String reason,
    String before,
    String after,
    String ip
) {

    public AuditEntry {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("An audit entry needs an action");
        }

        if (actorType == null) {
            throw new IllegalArgumentException("An audit entry needs an actor type");
        }

        if (resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("An audit entry needs a resource type");
        }

        // The one invariant the record enforces itself rather than leaving to the DB: a USER or
        // PROVIDER action without an actor is an audit row that cannot answer "who". SYSTEM is the
        // deliberate exception -- a job has no operator. Mirrors ck_audit_events_actor_id (V36).
        boolean hasActor = actorId != null && !actorId.isBlank();

        if (actorType == ActorType.SYSTEM && hasActor) {
            throw new IllegalArgumentException("A SYSTEM audit entry carries no actor id");
        }

        if (actorType != ActorType.SYSTEM && !hasActor) {
            throw new IllegalArgumentException("A " + actorType + " audit entry needs an actor id");
        }
    }

    /** Starts building an action performed by the given actor. */
    public static Builder builder(String action, ActorType actorType) {
        return new Builder(action, actorType);
    }

    /** Fluent assembly of the optional fields; see the record's javadoc for what each means. */
    public static final class Builder {

        private final String action;
        private final ActorType actorType;
        private String actorId;
        private MerchantId merchantId;
        private String resourceType;
        private String resourceId;
        private String reason;
        private String before;
        private String after;
        private String ip;

        private Builder(String action, ActorType actorType) {
            this.action = action;
            this.actorType = actorType;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder merchant(MerchantId merchantId) {
            this.merchantId = merchantId;
            return this;
        }

        public Builder resource(String resourceType, String resourceId) {
            this.resourceType = resourceType;
            this.resourceId = resourceId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder changing(String before, String after) {
            this.before = before;
            this.after = after;
            return this;
        }

        public Builder from(String ip) {
            this.ip = ip;
            return this;
        }

        public AuditEntry build() {
            return new AuditEntry(
                action, actorType, actorId, merchantId,
                resourceType, resourceId, reason, before, after, ip
            );
        }
    }
}
