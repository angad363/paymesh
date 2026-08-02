package com.paymesh.identity.application;

/** No such user. */
public final class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(String userId) {
        super("No user " + userId);
    }
}
