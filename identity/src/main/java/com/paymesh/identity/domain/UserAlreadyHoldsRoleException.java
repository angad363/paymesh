package com.paymesh.identity.domain;

/**
 * Granting a role the user already holds there.
 * <p>
 * Refused rather than silently deduplicated: it almost always means the caller believed something
 * false about the current state, and returning success would confirm the wrong belief.
 */
public final class UserAlreadyHoldsRoleException extends IllegalStateException {

    public UserAlreadyHoldsRoleException(UserId userId, Role role, String merchantId) {
        super("User " + userId.value() + " already holds " + role + " at merchant " + merchantId);
    }
}
