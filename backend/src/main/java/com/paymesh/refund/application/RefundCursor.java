package com.paymesh.refund.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Keyset pagination, ordered by {@code (createdAt DESC, refundId DESC)}. Same shape as
 * {@code OrderCursor} and {@code PaymentIntentCursor}, deliberately.
 * <p>
 * The id is the tiebreak and it is not optional: two refunds created in the same millisecond would
 * otherwise page inconsistently, repeating one and skipping the other.
 * <p>
 * Opaque on the wire, because a caller who can read a cursor will eventually construct one, and
 * then its format is a public contract nobody agreed to.
 */
public record RefundCursor(Instant createdAt, String refundId) {

    /** Above every real timestamp, so the first page starts before anything. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");

    /** Sorts above every generated id ('~' is above the digits, letters and '_' an id can hold). */
    private static final String LAST_POSSIBLE_ID = "~";

    private static final String SEPARATOR = "|";

    public RefundCursor {
        if (createdAt == null || refundId == null) {
            throw new IllegalArgumentException("A cursor needs both a timestamp and a refund id");
        }
    }

    public static RefundCursor start() {
        return new RefundCursor(END_OF_TIME, LAST_POSSIBLE_ID);
    }

    /** The first page when no cursor was supplied; the decoded position otherwise. */
    public static RefundCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return start();
        }

        String decoded;

        try {
            decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }

        int separator = decoded.lastIndexOf(SEPARATOR);

        if (separator < 1 || separator == decoded.length() - 1) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }

        try {
            return new RefundCursor(
                Instant.parse(decoded.substring(0, separator)),
                decoded.substring(separator + 1)
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (createdAt.toString() + SEPARATOR + refundId).getBytes(StandardCharsets.UTF_8)
        );
    }
}
