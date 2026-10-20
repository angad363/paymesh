package com.paymesh.shared.security;

/**
 * The caller is authenticated and scoped to the right merchant, but does not hold a role that
 * permits this action.
 * <p>
 * Distinct from {@code NoMerchantScopeException}, which means the caller could not be resolved to a
 * tenant at all. Both are 403; keeping them apart is what lets the message say which it was without
 * either one leaking whether a resource exists.
 */
public final class InsufficientRoleException extends RuntimeException {

    public InsufficientRoleException(CallerRole required) {
        super("This action requires the " + required + " role");
    }
}
