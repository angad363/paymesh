package com.paymesh.refund.application;

/** The merchant reference is already used by another of this merchant's refunds. */
public final class RefundAlreadyRequestedException extends RuntimeException {

    public RefundAlreadyRequestedException(String merchantReference) {
        super("A refund with reference " + merchantReference + " already exists");
    }
}
