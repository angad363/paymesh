package com.paymesh.order.domain;

import java.util.Locale;

/**
 * The order lifecycle.
 * <pre>
 * PENDING --cancel--------------&gt; CANCELLED
 *    |
 *    +--(payment, later)--&gt; PARTIALLY_PAID --&gt; PAID
 *    +--sweeper-----------&gt; EXPIRED
 * </pre>
 * {@code PENDING -> CANCELLED} (a merchant asks) and {@code PENDING -> EXPIRED} (the sweeper, once
 * {@code expiresAt} has passed) are reachable. {@code PAID} and {@code PARTIALLY_PAID} are still
 * not, and will not be until the outbox has a relay and Order has a consumer of
 * {@code payment.succeeded} -- <b>Payment does not write this column</b> (design spec 0.5), so an
 * order whose payment has succeeded stays PENDING in the API. That is a known, documented
 * inconsistency, not a bug report.
 */
public enum OrderStatus {
    PENDING,
    PARTIALLY_PAID,
    PAID,
    CANCELLED,
    EXPIRED;

    /**
     * Parses a caller-supplied status filter.
     * <p>
     * {@code valueOf} would do, but its message names the Java enum class, and an error body must
     * not hand a caller internal type names.
     */
    public static OrderStatus parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Order status cannot be null");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown order status: " + value);
        }
    }
}
