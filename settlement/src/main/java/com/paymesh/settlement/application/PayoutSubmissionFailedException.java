package com.paymesh.settlement.application;

/** The provider refused a payout, or could not be asked. Retried within the payout's budget. */
public final class PayoutSubmissionFailedException extends RuntimeException {

    public PayoutSubmissionFailedException(String message) {
        super(message);
    }

    public PayoutSubmissionFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
