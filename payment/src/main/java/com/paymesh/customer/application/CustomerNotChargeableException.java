package com.paymesh.customer.application;

/** A blocked customer cannot have a payment method attached. */
public final class CustomerNotChargeableException extends RuntimeException {

    public CustomerNotChargeableException(String customerId) {
        super("Customer " + customerId + " is blocked and cannot be charged");
    }
}
