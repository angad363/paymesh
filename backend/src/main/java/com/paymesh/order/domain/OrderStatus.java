package com.paymesh.order.domain;

import java.util.Locale;

/**
 * The order lifecycle.
 * <pre>
 * PENDING --cancel--------------&gt; CANCELLED
 *    |
 *    +--(payment, later)--&gt; PARTIALLY_PAID --&gt; PAID
 *    +--(expiry, later)---&gt; EXPIRED
 * </pre>
 * Only {@code PENDING -> CANCELLED} is reachable today. The other three are declared here (and in
 * the table's CHECK constraint) so the schema does not need a migration the moment Payment lands;
 * no code path produces them yet and none should be added speculatively.
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
