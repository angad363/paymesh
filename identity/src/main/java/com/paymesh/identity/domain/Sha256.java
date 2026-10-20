package com.paymesh.identity.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lowercase-hex SHA-256, used for the two values identity stores as a digest
 * rather than in the clear: refresh tokens and caller IP addresses.
 *
 * <p>Fast and unsalted on purpose. Both inputs are either high-entropy (a 256-bit
 * random token) or drawn from a space too small for salting to help (an IPv4
 * address), and both have to be findable by equality, which a per-row salt would
 * prevent. Passwords are the opposite case and use BCrypt instead.
 *
 * <p>Package-private: it is a domain implementation detail. Callers hash through
 * {@link RefreshToken#hash(String)} or {@link SecurityEvent#record}.
 */
final class Sha256 {

    private Sha256() {
    }

    static String hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every Java platform, so this cannot happen.
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
