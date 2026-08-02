package com.paymesh.order.domain;

/**
 * An order was asked to record a payment from a state where that means nothing.
 * <p>
 * IT LIVES IN {@code domain}, NOT {@code application}, and that is the same exception
 * {@code OrderNotCancellableException} and {@code OrderNotExpirableException} make: the aggregate
 * throws it, so putting it in {@code application} would invert the dependency direction --
 * {@code domain} would have to import the layer above it. ({@code java-coding-conventions} section 7
 * says business-rule failures live in {@code application} without acknowledging this case; open item
 * 15 already records the gap.)
 * <p>
 * There is no HTTP status here and no {@code ResponseStatusException}. Nothing translates this one at
 * an API boundary at all, in fact: the only caller is an event consumer, which has no HTTP response
 * to shape. It reaches the relay, which counts it, logs it and retries on the next pass.
 */
public class OrderPaymentNotApplicableException extends RuntimeException {

    private final OrderId orderId;
    private final OrderStatus status;

    public OrderPaymentNotApplicableException(OrderId orderId, OrderStatus status) {
        super("Order " + orderId.value() + " cannot record a payment while it is " + status);
        this.orderId = orderId;
        this.status = status;
    }

    public OrderId orderId() {
        return orderId;
    }

    public OrderStatus status() {
        return status;
    }
}
