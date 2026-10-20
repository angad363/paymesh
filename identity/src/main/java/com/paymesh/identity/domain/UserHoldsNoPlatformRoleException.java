package com.paymesh.identity.domain;

/**
 * This user does not hold that platform-wide role, so there is nothing to revoke.
 *
 * <p>Mapped to 404, the same as {@link UserHoldsNoRoleAtMerchantException} and for a different
 * reason: the caller here is already platform staff, so there is no enumeration to prevent. It is
 * 404 because the thing being deleted does not exist, which is what 404 means.
 */
public final class UserHoldsNoPlatformRoleException extends RuntimeException {

    public UserHoldsNoPlatformRoleException(UserId userId, Role role) {
        super("User " + userId.value() + " does not hold " + role + " platform-wide");
    }
}
