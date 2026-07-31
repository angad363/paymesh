package com.paymesh.order.application;

import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * A position in the order list: the last row a page handed out.
 * <p>
 * It carries BOTH {@code created_at} and {@code order_id}, and the second one is not decoration.
 * Two orders created in the same instant are indistinguishable to a cursor that stores only the
 * timestamp, so a page boundary falling between them either skips one or repeats one, depending on
 * which side of {@code <} the query lands. The order id breaks the tie, and because it is unique
 * the pair is a total ordering -- every row has exactly one position, so every row is handed out
 * exactly once.
 * <p>
 * Callers must treat the encoded form as opaque (conventions section 27). It is base64 of a
 * readable string rather than encryption: the guarantee is "do not parse this", not "you cannot".
 */
public record OrderCursor(Instant createdAt, String orderId) {

    /**
     * The position of the first page: later than any row can be, so nothing is excluded. Using a
     * sentinel rather than a null cursor keeps one query shape for both the first page and the
     * rest, which matters because a nullable timestamp parameter is where SQL type inference and
     * keyset predicates both get subtle.
     */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");

    /** Sorts above every generated id ('~' is above the digits, letters and '_' an id can hold). */
    private static final String LAST_POSSIBLE_ID = "~";

    private static final String SEPARATOR = "|";

    public OrderCursor {
        if (createdAt == null || orderId == null) {
            throw new IllegalArgumentException("A cursor needs both a timestamp and an order id");
        }
    }

    public static OrderCursor start() {
        return new OrderCursor(END_OF_TIME, LAST_POSSIBLE_ID);
    }

    /** The first page when no cursor was supplied; the decoded position otherwise. */
    public static OrderCursor decode(String encoded) {
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
            return new OrderCursor(
                Instant.parse(decoded.substring(0, separator)),
                decoded.substring(separator + 1)
            );
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid pagination cursor");
        }
    }

    public String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (createdAt.toString() + SEPARATOR + orderId).getBytes(StandardCharsets.UTF_8)
        );
    }

    public static OrderCursor of(Instant createdAt, String orderId) {
        return new OrderCursor(createdAt, orderId);
    }

    /**
     * Whether this position is strictly later than the given row -- that is, whether the row still
     * belongs to a page after this cursor. The same comparison the SQL predicate makes, kept here
     * so the port's contract has one definition rather than one per adapter.
     */
    public boolean isAfter(Instant rowCreatedAt, String rowOrderId) {
        int byTime = createdAt.compareTo(rowCreatedAt);

        return byTime > 0 || (byTime == 0 && orderId.compareTo(rowOrderId) > 0);
    }
}
