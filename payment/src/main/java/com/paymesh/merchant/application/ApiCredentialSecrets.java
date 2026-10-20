package com.paymesh.merchant.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How an API credential secret is hashed. One implementation, used by the issuer and the
 * authenticator.
 *
 * <h2>SHA-256, NOT BCRYPT, AND THE DIFFERENCE IS DELIBERATE</h2>
 *
 * This runs on <b>every API request</b>, where a deliberately slow KDF would be a self-inflicted
 * denial of service. bcrypt's cost exists to make guessing a low-entropy HUMAN password expensive;
 * this secret is 32 bytes from {@code SecureRandom} and is not guessable at any hash speed.
 * <p>
 * Identity's password hashing is bcrypt and must stay bcrypt, because a human chose that input.
 * The difference between the two is the entropy of what is being hashed, not carelessness in one of
 * them.
 * <p>
 * It lives in one class so the issuer and the authenticator cannot drift onto different algorithms
 * -- a divergence that would present as "every key is rejected" rather than as anything readable.
 */
public final class ApiCredentialSecrets {

    private ApiCredentialSecrets() {
    }

    public static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every JVM", impossible);
        }
    }

    /**
     * Constant-time over the hashes. A plain {@code equals} short-circuits on the first differing
     * byte and leaks the secret to anyone who can measure response time.
     */
    public static boolean matches(String presentedSecret, String storedHash) {
        return MessageDigest.isEqual(
            hash(presentedSecret).getBytes(StandardCharsets.UTF_8),
            storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}
