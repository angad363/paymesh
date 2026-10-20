package com.paymesh.merchant.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * Simulated verification. SDD 9.3 and 9.4.
 *
 * <h2>THIS IS THE KEY TO THE LOCK</h2>
 *
 * The merchant status gate refuses every write from a non-ACTIVE merchant, and registration
 * produces PENDING_VERIFICATION. Without a path out of that state the gate would freeze every newly
 * registered merchant out of the platform permanently -- which is exactly what happened when the
 * gate was built first and measured: 84 tests failed with 403 where a 201 was expected. Approval
 * here is what moves a merchant to ACTIVE. ADR-021.
 *
 * <h2>NO DOCUMENTS ARE STORED AND NONE ARE ACCEPTED</h2>
 *
 * PayMesh claims no compliance and processes no real money. A table holding scans of passports
 * would be the single worst thing in this repository, and it would be there to make a checklist
 * green rather than to verify anybody. What is recorded is a claimed legal name and registration
 * number, which is enough to model the workflow honestly.
 */
public record KycSubmission(
    KycSubmissionId kycSubmissionId,
    MerchantId merchantId,
    KycStatus status,
    String legalName,
    String registrationId,
    String reviewedBy,
    String reviewNotes,
    Instant reviewedAt,
    Instant submittedAt
) {

    public KycSubmission {
        if (kycSubmissionId == null || merchantId == null) {
            throw new IllegalArgumentException("A KYC submission must identify itself and its merchant");
        }

        if (status == null) {
            throw new IllegalArgumentException("A KYC submission must have a status");
        }

        legalName = requireText(legalName, "Legal name");
        registrationId = requireText(registrationId, "Registration identifier");

        // The Java mirror of ck_kyc_submissions_review. A row claiming to be APPROVED by nobody is
        // not an audit record.
        boolean decided = status != KycStatus.SUBMITTED;

        if (decided != (reviewedBy != null)) {
            throw new IllegalArgumentException(
                "A decided KYC submission names its reviewer; an undecided one does not"
            );
        }

        if (decided != (reviewedAt != null)) {
            throw new IllegalArgumentException(
                "A decided KYC submission records when it was decided; an undecided one does not"
            );
        }

        if (submittedAt == null) {
            throw new IllegalArgumentException("A KYC submission must have a submission instant");
        }
    }

    public static KycSubmission submit(
        MerchantId merchantId,
        String legalName,
        String registrationId,
        Instant submittedAt
    ) {
        return new KycSubmission(
            KycSubmissionId.generate(), merchantId, KycStatus.SUBMITTED, legalName, registrationId,
            null, null, null, submittedAt
        );
    }

    public KycSubmission approve(String reviewerId, String notes, Instant reviewedAt) {
        return decide(KycStatus.APPROVED, reviewerId, notes, reviewedAt);
    }

    public KycSubmission reject(String reviewerId, String notes, Instant reviewedAt) {
        return decide(KycStatus.REJECTED, reviewerId, notes, reviewedAt);
    }

    private KycSubmission decide(
        KycStatus outcome,
        String reviewerId,
        String notes,
        Instant reviewedAt
    ) {
        if (status != KycStatus.SUBMITTED) {
            throw new KycSubmissionAlreadyDecidedException(kycSubmissionId, status);
        }

        if (reviewerId == null || reviewerId.isBlank()) {
            throw new IllegalArgumentException("A KYC decision must name its reviewer");
        }

        return new KycSubmission(
            kycSubmissionId, merchantId, outcome, legalName, registrationId,
            reviewerId, notes == null || notes.isBlank() ? null : notes.strip(),
            reviewedAt, submittedAt
        );
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " is required");
        }

        return value.strip();
    }
}
