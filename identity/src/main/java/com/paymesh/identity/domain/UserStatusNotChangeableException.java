package com.paymesh.identity.domain;

/** A user status transition the state machine does not permit. */
public final class UserStatusNotChangeableException extends IllegalStateException {

    public UserStatusNotChangeableException(
        UserId userId,
        UserStatus actual,
        UserStatus requested
    ) {
        super("User " + userId.value() + " is " + actual + " and cannot become " + requested);
    }
}
