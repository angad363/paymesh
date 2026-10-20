package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.KycSubmission;
import com.paymesh.merchant.domain.KycSubmissionId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

public interface KycSubmissionRepository {

    /**
     * @throws KycSubmissionAlreadyOpenException when this merchant already has an undecided
     *     submission. Detected by {@code uq_kyc_submissions_open}, not by a pre-read: two
     *     concurrent submissions both find nothing and the partial unique index picks the winner.
     */
    KycSubmission save(KycSubmission submission);

    Optional<KycSubmission> findById(KycSubmissionId kycSubmissionId);

    List<KycSubmission> findByMerchant(MerchantId merchantId);
}
