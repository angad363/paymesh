package com.paymesh.customer.application;

import com.paymesh.customer.domain.CustomerId;

/**
 * Thrown both when the customer does not exist and when it exists under a different merchant.
 * The two cases are indistinguishable on purpose: telling a merchant "that id is real, just not
 * yours" leaks the existence of another tenant's data.
 */
public class CustomerNotFoundException extends RuntimeException {
    public CustomerNotFoundException(CustomerId customerId) {
        super("Customer not found: " + customerId.value());
    }
}
