package com.paymesh.order.domain;

/**
 * Raised when cancellation is requested from a state that cannot reach CANCELLED.
 * <p>
 * It lives in the domain because the aggregate is what refuses: the rule is the state machine, not
 * a policy the calling service applies. It carries no HTTP status -- the API layer decides that.
 */
public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(OrderId orderId, OrderStatus status) {
        super("Order " + orderId.value() + " cannot be cancelled while it is " + status.name());
    }
}
