package com.paymesh.risk.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How a denylisted value becomes a lookup key.
 *
 * <h2>PLAIN SHA-256, UNSALTED, AND THE REASON IS THE WHOLE POINT</h2>
 *
 * A salted hash cannot be looked up. The only operation this table supports is "is this exact value
 * denied?", which needs the same input to produce the same digest every time -- so a per-entry salt
 * would make the denylist unusable, and a single shared salt is a constant in the source that adds
 * a step without adding secrecy.
 * <p>
 * <b>So be honest about what this does and does not buy.</b> It is not password storage and must
 * never be reused as such: these values are low-entropy (a customer id, a device string), so
 * anyone holding a candidate list can confirm membership by hashing it. What it does buy is that a
 * dump of `denylist_entries` names nobody who is not already suspected, and that an operator
 * browsing the table cannot casually read a customer's identifier. That is the actual threat here.
 * <p>
 * If this ever needs to resist an offline attacker, the answer is encryption with a managed key
 * (the open ADR-006 question), not a slower hash -- a slow hash on a lookup path is a denial of
 * service on the money path.
 */
public final class DenylistHash {

    private DenylistHash() {
    }

    public static String of(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            throw new IllegalArgumentException("Denylisted value cannot be blank");
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawValue.trim().getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM ships SHA-256; the checked exception is an artefact of the 1997 API.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
