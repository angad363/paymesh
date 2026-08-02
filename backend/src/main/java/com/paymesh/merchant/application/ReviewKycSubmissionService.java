package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.KycStatus;
import com.paymesh.merchant.domain.KycSubmission;
import com.paymesh.merchant.domain.KycSubmissionId;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Submit verification, and decide it.
 *
 * <h2>APPROVAL IS WHAT ACTIVATES THE MERCHANT, IN THE SAME TRANSACTION</h2>
 *
 * The two must commit together. If the submission were approved and the activation failed, the
 * merchant would be permanently frozen with a green submission and no way to notice -- the exact
 * lock-with-no-key this whole capability exists to avoid, arrived at by a different route.
 *
 * <h2>Submission is the one write an unverified merchant may make</h2>
 *
 * {@code MerchantStatusFilter} exempts {@code /kyc-submissions} for that reason. Refusing it would
 * make verification unreachable, which is the deadlock rather than the fix.
 */
public final class ReviewKycSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(ReviewKycSubmissionService.class);

    private final KycSubmissionRepository submissions;
    private final ChangeMerchantStatusService merchantStatus;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReviewKycSubmissionService(
        KycSubmissionRepository submissions,
        ChangeMerchantStatusService merchantStatus,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.submissions = submissions;
        this.merchantStatus = merchantStatus;
        this.transactions = transactions;
        this.clock = clock;
    }

    public KycSubmission submit(MerchantId merchantId, String legalName, String registrationId) {
        return submissions.save(
            KycSubmission.submit(merchantId, legalName, registrationId, Instant.now(clock))
        );
    }

    /**
     * Approve, and activate the merchant with it.
     *
     * @param reviewerId the platform operator, carried into both the submission and the merchant's
     *     status history so the two records name the same person
     */
    public KycSubmission approve(KycSubmissionId id, String reviewerId, String notes) {
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            KycSubmission submission = require(id);
            KycSubmission approved = submissions.save(submission.approve(reviewerId, notes, now));

            // Activation and approval commit together. See the class comment.
            merchantStatus.activate(
                approved.merchantId(), reviewerId, "KYC approved: " + id.value()
            );

            log.warn(
                "KYC approved kycSubmissionId={} merchantId={} reviewer={}",
                id.value(), approved.merchantId().value(), reviewerId
            );

            return approved;
        });
    }

    /**
     * Reject, and leave the merchant exactly where it was.
     * <p>
     * Deliberately does NOT suspend or close: a rejected merchant is still a registration that may
     * be corrected and resubmitted, and closing it on a first rejection would make a typo terminal.
     */
    public KycSubmission reject(KycSubmissionId id, String reviewerId, String notes) {
        Instant now = Instant.now(clock);
        KycSubmission rejected = submissions.save(require(id).reject(reviewerId, notes, now));

        log.warn(
            "KYC rejected kycSubmissionId={} merchantId={} reviewer={}",
            id.value(), rejected.merchantId().value(), reviewerId
        );

        return rejected;
    }

    public List<KycSubmission> list(MerchantId merchantId) {
        return submissions.findByMerchant(merchantId);
    }

    private KycSubmission require(KycSubmissionId id) {
        return submissions.findById(id)
            .orElseThrow(() -> new KycSubmissionNotFoundException(id.value()));
    }

    /** Exposed so a caller can tell "still waiting" from "never asked". */
    public boolean hasOpenSubmission(MerchantId merchantId) {
        return submissions.findByMerchant(merchantId).stream()
            .anyMatch(submission -> submission.status() == KycStatus.SUBMITTED);
    }
}
