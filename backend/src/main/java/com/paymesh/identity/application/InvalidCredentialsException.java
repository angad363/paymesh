package com.paymesh.identity.application;

/**
 * Raised for both an unknown email and a wrong password, with one message for
 * both. Telling the caller which of the two failed turns the login endpoint into
 * an account-enumeration oracle (REST conventions section 36: do not reveal
 * whether an internal account exists).
 */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password.");
    }
}
