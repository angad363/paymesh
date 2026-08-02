package com.paymesh.merchant.application;

/** No such submission. */
public final class KycSubmissionNotFoundException extends RuntimeException {

    public KycSubmissionNotFoundException(String kycSubmissionId) {
        super("No KYC submission " + kycSubmissionId);
    }
}
