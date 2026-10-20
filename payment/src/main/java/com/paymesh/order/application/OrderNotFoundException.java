package com.paymesh.order.application;

import com.paymesh.order.domain.OrderId;

/**
 * Thrown both when the order does not exist and when it exists under a different merchant.
 * The two cases are indistinguishable on purpose: telling a merchant "that id is real, just not
 * yours" leaks the existence of another tenant's data.
 */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(OrderId orderId) {
        super("Order not found: " + orderId.value());
    }
}
