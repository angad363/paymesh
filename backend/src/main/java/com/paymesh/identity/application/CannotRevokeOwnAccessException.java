package com.paymesh.identity.application;

import com.paymesh.identity.domain.UserId;

/**
 * A merchant admin revoking their own access.
 * <p>
 * Refused because they are the only role that can grant it back: a merchant with one admin who
 * revoked themselves would have no way to administer their own account and would need PayMesh to
 * intervene. Better to refuse the action than to make it recoverable.
 */
public final class CannotRevokeOwnAccessException extends RuntimeException {

    public CannotRevokeOwnAccessException(UserId userId) {
        super("User " + userId.value() + " cannot revoke their own access");
    }
}
