package com.paymesh.order.domain;

import java.util.Locale;

/**
 * The order lifecycle.
 * <pre>
 * PENDING --cancel-----------------------------&gt; CANCELLED
 *    |
 *    +--payment.succeeded, captured &lt; amount---&gt; PARTIALLY_PAID
 *    +--payment.succeeded, captured = amount---&gt; PAID
 *    +--sweeper--------------------------------&gt; EXPIRED
 * </pre>
 * <b>All four are reachable from PENDING, and none of them from each other.</b> {@code PAID} and
 * {@code PARTIALLY_PAID} were the last to arrive: they sat here and in {@code ck_orders_status}
 * unreachable from V5 until the outbox gained a relay and Order gained a consumer of
 * {@code payment.succeeded} (ADR-016). {@code Order.markPaid} is the only route to either.
 * <p>
 * <b>Payment still does not write this column</b> (design spec 0.5). Order moves it itself, on an
 * event, in Order's own code -- which is why {@code ModuleBoundaryTest.orderNeverImportsPayment}
 * still has an empty allowlist.
 * <p>
 * PARTIALLY_PAID does not lead to PAID. A second collection against one order is structurally
 * impossible today: an order holds at most one live payment intent, for exactly its own amount
 * (ADR-011), and a partial capture ends that intent in a terminal state. The arrow will exist when
 * split payments do, and not before.
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
