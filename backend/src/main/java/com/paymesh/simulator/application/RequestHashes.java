package com.paymesh.simulator.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SHA-256 over the canonical fields of a request, so a repeated idempotency key can be told from a
 * reused one.
 * <p>
 * <b>Over the parsed fields, not the raw bytes, and that is a deliberate difference from
 * {@code IdempotencyFilter}.</b> That layer hashes raw bytes precisely so it never has to parse
 * attacker-controlled JSON before making a deduplication decision -- a normalisation bug there would
 * replay the wrong response to an unauthenticated caller. Here the body has already been parsed and
 * validated by Bean Validation before any service sees it, and the caller is PayMesh holding the
 * simulator's own key. Hashing the fields means a caller that reformats its JSON, or reorders its
 * keys, is not told its retry is a conflict.
 */
final class RequestHashes {

    private static final String DIGEST = "SHA-256";

    /**
     * The separator, and it is not decorative. Without a delimiter the fields {@code ("ab", "c")}
     * and {@code ("a", "bc")} hash identically, so two genuinely different requests would look like
     * a replay of each other. A unit separator cannot appear in any of the values being joined.
     */
    private static final String SEPARATOR = "";

    private RequestHashes() {
    }

    static String of(Object... fields) {
        String canonical = Stream.of(fields)
            .map(field -> field == null ? "" : field.toString())
            .collect(Collectors.joining(SEPARATOR));

        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance(DIGEST).digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(DIGEST + " is required by every JVM", impossible);
        }
    }
}
