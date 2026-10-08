package com.paymesh.risk.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.List;

/**
 * One evaluation, and the evidence for it. <b>Immutable, like a ledger entry and for the same
 * reason</b> (ADR-018): this is the record of a decision that was made, and a decision that can be
 * edited afterwards is not evidence of anything.
 * <p>
 * There is no {@code revise()} and no setter. A later, different opinion is a new assessment.
 *
 * @param rulesetVersion the {@link RiskRuleset#VERSION} that produced this. Stored rather than
 *                       joined so the row stays readable after the rules change -- the whole point
 *                       of SDD §14.6's reproducibility requirement.
 * @param features       the inputs, verbatim. Together with the version, these reproduce the
 *                       outcome exactly.
 */
public record RiskAssessment(
    RiskAssessmentId assessmentId,
    MerchantId merchantId,
    String paymentIntentId,
    RiskOutcome outcome,
    List<String> matchedRules,
    int rulesetVersion,
    RiskFeatures features,
    Instant decidedAt
) {

    public RiskAssessment {
        if (assessmentId == null) {
            throw new IllegalArgumentException("Risk assessment identifier cannot be null");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Risk assessment merchant cannot be null");
        }

        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("Risk assessment payment intent cannot be blank");
        }

        if (outcome == null) {
            throw new IllegalArgumentException("Risk assessment outcome cannot be null");
        }

        if (features == null) {
            throw new IllegalArgumentException("Risk assessment features cannot be null");
        }

        if (decidedAt == null) {
            throw new IllegalArgumentException("Risk assessment decision instant cannot be null");
        }

        if (rulesetVersion < 1) {
            throw new IllegalArgumentException("Risk assessment ruleset version must be at least 1");
        }

        matchedRules = List.copyOf(matchedRules == null ? List.of() : matchedRules);
    }

    /**
     * Mints an assessment from a verdict. The ONLY way to make one, so an assessment can never
     * disagree with the ruleset that supposedly produced it.
     */
    public static RiskAssessment record(
        MerchantId merchantId,
        String paymentIntentId,
        RiskRuleset.Verdict verdict,
        RiskFeatures features,
        Instant decidedAt
    ) {
        if (verdict == null) {
            throw new IllegalArgumentException("Risk assessment verdict cannot be null");
        }

        return new RiskAssessment(
            RiskAssessmentId.generate(),
            merchantId,
            paymentIntentId,
            verdict.outcome(),
            verdict.matchedRules(),
            RiskRuleset.VERSION,
            features,
            decidedAt
        );
    }

    /** What Payment asks. Delegated so no caller has to know which outcomes are permissive. */
    public boolean permitsConfirmation() {
        return outcome.permitsConfirmation();
    }
}
