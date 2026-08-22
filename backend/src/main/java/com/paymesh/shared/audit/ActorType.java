package com.paymesh.shared.audit;

/**
 * Who performed an audited action. Mirrors {@code ck_audit_events_actor_type} (V36).
 *
 * <p>Three kinds, because "who" has three genuinely different answers on the money path:
 */
public enum ActorType {

    /** A human acting through the API, identified by their {@code usr_} id. Carries an actor id. */
    USER,

    /**
     * A scheduled job or internal process with no operator behind it -- reconciliation replaying a
     * provider's record, a release sweep. Carries no actor id, and {@code ck_audit_events_actor_id}
     * refuses one.
     */
    SYSTEM,

    /** A provider acting through a signed callback. Carries the provider name as its actor id. */
    PROVIDER
}
