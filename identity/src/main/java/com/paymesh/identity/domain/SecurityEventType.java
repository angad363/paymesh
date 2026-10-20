package com.paymesh.identity.domain;

public enum SecurityEventType {
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    TOKEN_REFRESHED,
    /** A refresh token was presented twice. The whole family is revoked in response. */
    REFRESH_TOKEN_REUSE_DETECTED,
    LOGGED_OUT,

    /**
     * PLATFORM SCOPE: the human is barred from PayMesh entirely, across every tenant they belong
     * to. Distinct from LOGGED_OUT even though suspension revokes sessions -- logging a suspension
     * as a sign-out would make "who was barred and when" unanswerable from the table that exists
     * to answer it.
     */
    USER_SUSPENDED,
    USER_REACTIVATED,
    USER_CLOSED,

    /**
     * MERCHANT SCOPE: they lost their roles at one merchant and kept their account. The
     * departed-employee case, and deliberately not the same event as a suspension -- one is the
     * merchant's own business, the other is the platform's (ADR-024).
     */
    MERCHANT_ACCESS_REVOKED,

    /**
     * PLATFORM SCOPE, and the widest grant this platform has: authority over every tenant,
     * including the power to activate, suspend and close merchants (ADR-027).
     * <p>
     * Both of these revoke every live session -- promotion so the new claim is reissued at once,
     * demotion so the old one stops working -- so without their own names both would show up in
     * the log as somebody signing out.
     */
    PLATFORM_ADMIN_GRANTED,
    PLATFORM_ADMIN_REVOKED
}
