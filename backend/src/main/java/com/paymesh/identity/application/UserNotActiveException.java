package com.paymesh.identity.application;

import com.paymesh.identity.domain.UserStatus;

/**
 * The password was correct but the account may not open a session. Distinct from
 * InvalidCredentialsException because the caller has already proved who they are:
 * this is an authorization failure (403), not an authentication one (401), and it
 * discloses nothing they did not already know.
 */
public class UserNotActiveException extends RuntimeException {
    public UserNotActiveException(UserStatus status) {
        super("User account is " + status.name() + " and cannot sign in.");
    }
}
