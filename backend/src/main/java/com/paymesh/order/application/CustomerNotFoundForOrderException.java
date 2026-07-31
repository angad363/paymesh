package com.paymesh.order.application;

/**
 * The order named a customer this merchant does not have.
 * <p>
 * Raised identically whether the customer never existed or belongs to another merchant: the caller
 * must not be able to use the order endpoint as an oracle for whether some other tenant's customer
 * id is real. The message quotes back only the id the caller already sent.
 */
public class CustomerNotFoundForOrderException extends RuntimeException {
    public CustomerNotFoundForOrderException(String customerId) {
        super("Customer not found: " + customerId);
    }
}
