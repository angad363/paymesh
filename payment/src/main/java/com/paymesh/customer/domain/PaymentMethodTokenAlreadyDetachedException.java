package com.paymesh.customer.domain;

/** Detaching a token that is already detached. */
public final class PaymentMethodTokenAlreadyDetachedException extends IllegalStateException {

    public PaymentMethodTokenAlreadyDetachedException(PaymentMethodTokenId id) {
        super("Payment method token " + id.value() + " is already detached");
    }
}
