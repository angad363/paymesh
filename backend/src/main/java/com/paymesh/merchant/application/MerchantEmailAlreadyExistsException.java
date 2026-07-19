package com.paymesh.merchant.application;

public class MerchantEmailAlreadyExistsException extends RuntimeException {
    public MerchantEmailAlreadyExistsException(String email) {
        super("A merchant already exists with email " + email);
    }
}
