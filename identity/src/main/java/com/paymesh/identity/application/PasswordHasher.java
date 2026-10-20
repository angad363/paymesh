package com.paymesh.identity.application;

/**
 * Password hashing port. The implementation is BCrypt, which lives in Spring
 * Security, so it stays behind this interface and out of the application layer --
 * the same reason MerchantRepository exists rather than a direct JPA call.
 */
public interface PasswordHasher {

    String hash(String rawPassword);

    /**
     * Constant-time verification of a password against its stored hash.
     * Returns false rather than throwing on a malformed hash.
     */
    boolean matches(String rawPassword, String passwordHash);
}
