package com.paymesh.merchant.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Maps {@code kyc_submissions} (V17). */
@Entity
@Table(name = "kyc_submissions")
public class KycSubmissionJpaEntity {

    @Id
    @Column(name = "kyc_submission_id", nullable = false, length = 40)
    private String kycSubmissionId;

    @Column(name = "merchant_id", nullable = false, length = 40)
    private String merchantId;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "legal_name", nullable = false, length = 200)
    private String legalName;

    @Column(name = "registration_id", nullable = false, length = 100)
    private String registrationId;

    @Column(name = "reviewed_by", length = 80)
    private String reviewedBy;

    @Column(name = "review_notes", length = 500)
    private String reviewNotes;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    protected KycSubmissionJpaEntity() {
    }

    public KycSubmissionJpaEntity(
        String kycSubmissionId,
        String merchantId,
        String status,
        String legalName,
        String registrationId,
        String reviewedBy,
        String reviewNotes,
        Instant reviewedAt,
        Instant submittedAt
    ) {
        this.kycSubmissionId = kycSubmissionId;
        this.merchantId = merchantId;
        this.status = status;
        this.legalName = legalName;
        this.registrationId = registrationId;
        this.reviewedBy = reviewedBy;
        this.reviewNotes = reviewNotes;
        this.reviewedAt = reviewedAt;
        this.submittedAt = submittedAt;
    }

    public String kycSubmissionId() {
        return kycSubmissionId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String status() {
        return status;
    }

    public String legalName() {
        return legalName;
    }

    public String registrationId() {
        return registrationId;
    }

    public String reviewedBy() {
        return reviewedBy;
    }

    public String reviewNotes() {
        return reviewNotes;
    }

    public Instant reviewedAt() {
        return reviewedAt;
    }

    public Instant submittedAt() {
        return submittedAt;
    }
}
