package com.paymesh.merchant.domain;

/** Deciding a submission that has already been approved or rejected. */
public final class KycSubmissionAlreadyDecidedException extends IllegalStateException {

    public KycSubmissionAlreadyDecidedException(KycSubmissionId id, KycStatus status) {
        super("KYC submission " + id.value() + " is already " + status);
    }
}
