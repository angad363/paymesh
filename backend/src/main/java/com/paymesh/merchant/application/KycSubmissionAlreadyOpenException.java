package com.paymesh.merchant.application;

/**
 * This merchant already has an undecided submission.
 * <p>
 * Refused rather than queued: a merchant able to file a hundred submissions leaves an operator with
 * no idea which one is the live request, and the partial unique index is what makes "one open
 * request" true rather than customary.
 */
public final class KycSubmissionAlreadyOpenException extends RuntimeException {

    public KycSubmissionAlreadyOpenException(String merchantId) {
        super("Merchant " + merchantId + " already has a KYC submission awaiting review");
    }
}
