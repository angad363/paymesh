package com.paymesh.identity.infrastructure.security;

import com.paymesh.identity.application.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * BCrypt implementation of the PasswordHasher port.
 *
 * <p>BCrypt rather than a general-purpose digest because a password is a
 * low-entropy secret: the point is that verifying it is deliberately slow, and
 * that every hash carries its own salt so one leaked table cannot be attacked
 * with a precomputed rainbow table. The cost factor is embedded in each stored
 * hash, so raising it later re-hashes on next login instead of invalidating
 * existing passwords.
 *
 * <p>{@code matches} on the encoder is constant-time and returns false on a
 * malformed hash rather than throwing, which is what the port promises.
 */
public final class BCryptPasswordHasher implements PasswordHasher {

    private final BCryptPasswordEncoder encoder;

    public BCryptPasswordHasher(int strength) {
        this.encoder = new BCryptPasswordEncoder(strength);
    }

    @Override
    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    @Override
    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
