package com.paymesh.order.domain;

/**
 * Raised when expiry is requested for an order that cannot reach EXPIRED.
 * <p>
 * It lives in the domain because the aggregate is what refuses: the rule is the state machine, not
 * a policy the calling service applies. It carries no HTTP status -- and unlike
 * {@link OrderNotCancellableException} it has no exception handler either, because <b>no endpoint
 * can raise it</b>. Expiry has no route; the only caller is the sweeper, which re-checks eligibility
 * under a row lock and skips rather than asking. Reaching this exception therefore means the
 * sweeper's check and the aggregate's disagree, which is a bug and should surface as one.
 */
public class OrderNotExpirableException extends RuntimeException {
    public OrderNotExpirableException(OrderId orderId, OrderStatus status) {
        super("Order " + orderId.value() + " cannot expire while it is " + status.name());
    }
}
