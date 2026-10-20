package com.paymesh.identity.application;

public class UserEmailAlreadyExistsException extends RuntimeException {
    public UserEmailAlreadyExistsException(String email) {
        super("A user already exists with email " + email);
    }
}
