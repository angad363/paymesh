package com.paymesh.payment.application;

/**
 * ONE EXCEPTION FOR THREE CAUSES, DELIBERATELY: the order does not exist, the order belongs to
 * another merchant, or the order is not in a payable state.
 * <p>
 * Splitting them would turn create into an oracle for enumerating another tenant's order ids --
 * "not payable" and "not yours" would be distinguishable, and the second answer confirms the id is
 * real. The message names the order id the caller already supplied and nothing else about it.
 */
public class OrderNotPayableException extends RuntimeException {
    public OrderNotPayableException(String orderId) {
        super("Order cannot be paid: " + orderId);
    }
}
