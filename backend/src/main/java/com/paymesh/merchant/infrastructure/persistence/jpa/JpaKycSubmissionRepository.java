package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.application.KycSubmissionAlreadyOpenException;
import com.paymesh.merchant.application.KycSubmissionRepository;
import com.paymesh.merchant.domain.KycStatus;
import com.paymesh.merchant.domain.KycSubmission;
import com.paymesh.merchant.domain.KycSubmissionId;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

public final class JpaKycSubmissionRepository implements KycSubmissionRepository {

    private final SpringDataKycSubmissionRepository submissions;

    public JpaKycSubmissionRepository(SpringDataKycSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    @Override
    public KycSubmission save(KycSubmission submission) {
        try {
            return toDomain(submissions.saveAndFlush(toEntity(submission)));
        } catch (DataIntegrityViolationException exception) {
            // uq_kyc_submissions_open is partial and unnamed in the violation on some paths, so it
            // is matched by name where possible and otherwise assumed: the only unique constraint a
            // caller can collide with on this table is the one-open-submission rule.
            throw new KycSubmissionAlreadyOpenException(submission.merchantId().value());
        }
    }

    @Override
    public Optional<KycSubmission> findById(KycSubmissionId kycSubmissionId) {
        return submissions.findById(kycSubmissionId.value()).map(JpaKycSubmissionRepository::toDomain);
    }

    @Override
    public List<KycSubmission> findByMerchant(MerchantId merchantId) {
        return submissions.findByMerchantIdOrderBySubmittedAtDesc(merchantId.value())
            .stream()
            .map(JpaKycSubmissionRepository::toDomain)
            .toList();
    }

    private static KycSubmissionJpaEntity toEntity(KycSubmission submission) {
        return new KycSubmissionJpaEntity(
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

    private static KycSubmission toDomain(KycSubmissionJpaEntity entity) {
        return new KycSubmission(
            KycSubmissionId.from(entity.kycSubmissionId()),
            MerchantId.from(entity.merchantId()),
            KycStatus.valueOf(entity.status()),
            entity.legalName(),
            entity.registrationId(),
            entity.reviewedBy(),
            entity.reviewNotes(),
            entity.reviewedAt(),
            entity.submittedAt()
        );
    }
}
