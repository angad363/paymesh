package com.paymesh.customer.application;

/** No such token, or it belongs to another merchant. One answer for both (ADR-007). */
public final class PaymentMethodTokenNotFoundException extends RuntimeException {

    public PaymentMethodTokenNotFoundException(String tokenId) {
        super("No payment method token " + tokenId);
    }
}
