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
    /** Platform-wide operator. Not grantable yet -- user_roles requires a merchant scope. */
    PLATFORM_ADMIN,
    /** Full control over one merchant. */
    MERCHANT_ADMIN,
    /** Day-to-day access to one merchant. */
    MERCHANT_USER,
    /** Machine identity. Not grantable yet -- user_roles requires a merchant scope. */
    SERVICE_ACCOUNT
}
