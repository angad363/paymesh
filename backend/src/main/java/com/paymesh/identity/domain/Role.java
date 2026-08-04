package com.paymesh.identity.domain;

/**
 * The four identity kinds PayMesh supports (SDD 8.1). Modelled as an enum rather
 * than the SDD's `roles` table: the set only changes with a code deploy, and a
 * lookup table would add a join plus a second source of truth for four values.
 *
 * <p>A role narrows what a token may attempt. It never replaces the tenant check
 * a repository performs (SDD 8.6).
 */
public enum Role {
    /**
     * Platform-wide operator. Held WITHOUT a merchant, and that is enforced by
     * {@code ck_user_roles_scope} rather than only here (V23, ADR-027).
     *
     * <p>A PLATFORM_ADMIN scoped to a merchant would be that merchant's own staff holding the
     * power to lift their own suspension, which would make suspension advisory. So the scope is
     * not "any merchant" -- it is no merchant, and the database refuses the other shape.
     */
    PLATFORM_ADMIN,
    /** Full control over one merchant. */
    MERCHANT_ADMIN,
    /** Day-to-day access to one merchant. */
    MERCHANT_USER,
    /**
     * Machine identity. Still not grantable by any endpoint, but no longer for the reason V2 gave.
     *
     * <p>Machines authenticate with merchant API credentials (ADR-022), which belong to a merchant
     * -- so a platform-wide service account is a different thing needing a different issuer, not a
     * row this table was merely unable to hold. It stays merchant-scoped in
     * {@code ck_user_roles_scope} so the door is shut rather than ajar.
     */
    SERVICE_ACCOUNT;

    /**
     * True when this role is held platform-wide rather than at one merchant.
     *
     * <p>The single source of the rule that {@code ck_user_roles_scope} states in SQL. Everything
     * that has to decide whether a merchant id belongs with a role -- {@link RoleAssignment}, the
     * token encoder, the grant endpoints -- asks here rather than naming the constant, so adding a
     * second platform role means changing this method and the CHECK, and nothing else.
     */
    public boolean isPlatformScoped() {
        return this == PLATFORM_ADMIN;
    }
}
