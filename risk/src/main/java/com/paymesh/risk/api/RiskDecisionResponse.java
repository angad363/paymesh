package com.paymesh.risk.api;

import com.paymesh.risk.domain.RiskAssessment;

/**
 * What Payment gets back: a boolean and an id, deliberately -- see {@code RiskCheck}'s javadoc on
 * the backend side for why the matched rules never cross this wire (a free oracle for whoever can
 * retry a confirm and watch which message changes).
 */
public record RiskDecisionResponse(boolean permitted, String assessmentId) {

    public static RiskDecisionResponse from(RiskAssessment assessment) {
        return new RiskDecisionResponse(
            assessment.permitsConfirmation(), assessment.assessmentId().value()
        );
    }
}
