package com.paymesh.customer.domain;

/** A customer is already in the status the caller asked for. */
public final class CustomerStatusNotChangeableException extends IllegalStateException {

    private final CustomerId customerId;
    private final CustomerStatus actual;

    public CustomerStatusNotChangeableException(
        CustomerId customerId,
        CustomerStatus actual,
        CustomerStatus requested
    ) {
        super("Customer " + customerId.value() + " is already " + actual);

        this.customerId = customerId;
        this.actual = actual;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public CustomerStatus actual() {
        return actual;
    }
}
