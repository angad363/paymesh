package com.paymesh.audit.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256, hex-encoded, for the fields the audit log stores hashed rather than plaintext.
 *
 * <h2>WHY HASH AT ALL</h2>
 *
 * before/after and the source IP must not sit in the log as values: a rotated signing secret would
 * be readable in {@code before_hash}, and an address would be PII the log would then have to protect.
 * A hash proves THAT the state changed and lets two rows be compared for equality ("same source",
 * "reverted to the prior value") without storing what the value was.
 *
 * <h2>THE CEILING, NAMED</h2>
 */
// ponytail: plain unsalted SHA-256. A low-entropy before/after (e.g. "ACTIVE"->"SUSPENDED") is
// guessable, and an IPv4 address is brute-forceable from its digest. That is acceptable today
// because the fields hold no secret a guess would reveal and NO wired caller passes an IP yet
// (the service-layer recorders run below the HTTP boundary). Upgrade path when a controller-layer
// caller passes a real IP: HMAC-SHA256 under a configured pepper, so the digest cannot be reversed.
public final class AuditHashing {

    private AuditHashing() {
    }

    /** @return the hex SHA-256 of {@code value}, or null when {@code value} is null. */
    public static String sha256(String value) {
        if (value == null) {
            return null;
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; MessageDigest names it as guaranteed. Unreachable.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
