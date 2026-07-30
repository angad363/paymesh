package com.paymesh.customer.application;

public class CustomerReferenceAlreadyExistsException extends RuntimeException {
    public CustomerReferenceAlreadyExistsException(String merchantReference) {
        super("A customer already exists with merchant reference " + merchantReference);
    }
}
