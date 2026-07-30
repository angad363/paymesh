package com.paymesh.identity.domain;

public enum SecurityEventType {
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    TOKEN_REFRESHED,
    /** A refresh token was presented twice. The whole family is revoked in response. */
    REFRESH_TOKEN_REUSE_DETECTED,
    LOGGED_OUT
}
