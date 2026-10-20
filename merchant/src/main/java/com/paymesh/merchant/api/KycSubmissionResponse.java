package com.paymesh.merchant.api;

import com.paymesh.merchant.domain.KycSubmission;

import java.time.Instant;

public record KycSubmissionResponse(
    String id,
    String merchantId,
    String status,
    String legalName,
    String registrationId,
    String reviewedBy,
    String reviewNotes,
    Instant reviewedAt,
    Instant submittedAt
) {

    public static KycSubmissionResponse from(KycSubmission submission) {
        return new KycSubmissionResponse(
            submission.kycSubmissionId().value(),
            submission.merchantId().value(),
            submission.status().name(),
            submission.legalName(),
            submission.registrationId(),
            submission.reviewedBy(),
            submission.reviewNotes(),
            submission.reviewedAt(),
            submission.submittedAt()
        );
    }
}
