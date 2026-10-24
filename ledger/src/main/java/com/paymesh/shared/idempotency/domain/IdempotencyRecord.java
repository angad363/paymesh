package com.paymesh.shared.idempotency.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * One remembered attempt at one write, scoped to {@code merchant + endpoint + Idempotency-Key}.
 * <p>
 * The scope is the identity: there is no surrogate id and no {@code idm_} prefix, because nothing
 * outside the server ever names one of these rows and ADR-003 governs identifiers that appear in an
 * API.
 * <p>
 * The invariants below are the Java half of the CHECK constraints in
 * {@code V4__create_idempotency_records.sql}. Both exist on purpose: the constraint stops any
 * writer, this stops the one that matters most from getting as far as the database.
 *
 * @param requestHash    lowercase-hex SHA-256 of the raw request body bytes. It answers "is this
 *                       the same request?"; a different hash under the same key is the caller
 *                       reusing a key for something else, never a retry.
 * @param responseStatus null exactly while IN_PROGRESS
 * @param responseBody   may be null even when COMPLETED -- a 204 has no body
 */
public record IdempotencyRecord(
    MerchantId merchantId,
    String endpoint,
    String idempotencyKey,
    String requestHash,
    IdempotencyStatus status,
    Integer responseStatus,
    String responseBody,
    Instant createdAt,
    Instant completedAt
) {

    /** Column width of idempotency_records.idempotency_key, and therefore the accepted key length. */
    public static final int MAX_KEY_LENGTH = 255;

    private static final int MAX_ENDPOINT_LENGTH = 200;
    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

    public IdempotencyRecord {
        if (merchantId == null) {
            throw new IllegalArgumentException("Idempotency record must be scoped to a merchant");
        }

        if (endpoint == null || endpoint.isBlank() || endpoint.length() > MAX_ENDPOINT_LENGTH) {
            throw new IllegalArgumentException("Idempotency record must name an endpoint");
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()
            || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("Idempotency key must be 1 to 255 characters");
        }

        if (requestHash == null || !SHA_256_HEX.matcher(requestHash).matches()) {
            throw new IllegalArgumentException("Request hash must be lowercase hex SHA-256");
        }

        if (status == null) {
            throw new IllegalArgumentException("Idempotency record must have a status");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException("Idempotency record must have a creation instant");
        }

        boolean hasResponse = responseStatus != null && completedAt != null;

        if (status == IdempotencyStatus.COMPLETED != hasResponse) {
            // Either an IN_PROGRESS row carrying a response (which a racing request would replay as
            // if the work had finished) or a COMPLETED row with nothing to replay.
            throw new IllegalArgumentException(
                "A COMPLETED record must carry a response status and a completion instant, "
                    + "and an IN_PROGRESS record must carry neither"
            );
        }
    }

    public static IdempotencyRecord started(
        MerchantId merchantId,
        String endpoint,
        String idempotencyKey,
        String requestHash,
        Instant startedAt
    ) {
        return new IdempotencyRecord(
            merchantId,
            endpoint,
            idempotencyKey,
            requestHash,
            IdempotencyStatus.IN_PROGRESS,
            null,
            null,
            startedAt,
            null
        );
    }

    public IdempotencyRecord completedWith(int responseStatus, String responseBody, Instant at) {
        return new IdempotencyRecord(
            merchantId,
            endpoint,
            idempotencyKey,
            requestHash,
            IdempotencyStatus.COMPLETED,
            responseStatus,
            responseBody,
            createdAt,
            at
        );
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    /**
     * Lowercase-hex SHA-256 of the raw body bytes, hashed before any parsing so that two requests
     * differing only in whitespace are correctly treated as different requests. Unsalted and fast on
     * purpose: this is an equality key, not a credential.
     */
    public static String hashOf(byte[] requestBody) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(requestBody));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is required of every Java platform, so this cannot happen.
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
