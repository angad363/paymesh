package com.paymesh.order.application;

public class OrderReferenceAlreadyExistsException extends RuntimeException {
    public OrderReferenceAlreadyExistsException(String merchantOrderReference) {
        super("An order already exists with merchant order reference " + merchantOrderReference);
    }
}
