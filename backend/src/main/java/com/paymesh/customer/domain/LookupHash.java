package com.paymesh.customer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Deterministic one-way lookup key for a PII value (SDD 10.6: search matches on hashes, display
 * reads the value itself). Same input always yields the same 64-char lowercase hex digest, which is
 * what makes an exact-match index possible over a column that will eventually hold ciphertext.
 * <p>
 * Lives in the domain, not the mapper, because the hash must be taken of the NORMALIZED value --
 * that pairing is a domain rule, and splitting it across layers is how the two silently drift.
 */
final class LookupHash {

    private LookupHash() {
    }

    // ponytail: unsalted SHA-256. It is a lookup key, not a secret -- an attacker holding this
    // column already holds the plaintext one next to it (ADR-006). When encryption and key
    // management land, this becomes HMAC-SHA256 under a peppered key so the hash stops being
    // reversible by dictionary, and existing rows are rehashed in the same migration.
    static String of(String normalizedValue) {
        if (normalizedValue == null) {
            return null;
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(normalizedValue.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the JDK spec; unreachable on any conformant runtime.
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
