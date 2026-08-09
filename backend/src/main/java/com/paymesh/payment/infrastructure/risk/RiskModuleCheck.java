package com.paymesh.payment.infrastructure.risk;

import com.paymesh.payment.application.RiskCheck;
import com.paymesh.risk.application.EvaluateRiskCommand;
import com.paymesh.risk.application.EvaluateRiskService;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Payment's {@link RiskCheck}, answered by the Risk module.
 * <p>
 * The same shape as {@code OrderModuleLookup} and for the same reason (ADR-008): the adapter lives
 * in the CONSUMER's infrastructure, so Payment's application layer never names a Risk type and Risk
 * never learns that Payment exists. When Risk is extracted into its own service (SDD §30.1 lists it
 * among the first to go), this class becomes an HTTP client and nothing on either side of it
 * changes.
 * <p>
 * <b>It flattens the assessment to a boolean deliberately.</b> The matched rules stay on the Risk
 * side of this line -- see {@link RiskCheck} for why handing them back would be a free oracle.
 */
public final class RiskModuleCheck implements RiskCheck {

    private final EvaluateRiskService evaluateRisk;

    public RiskModuleCheck(EvaluateRiskService evaluateRisk) {
        this.evaluateRisk = evaluateRisk;
    }

    @Override
    public Decision evaluate(
        MerchantId merchantId,
        String paymentIntentId,
        long amountMinor,
        String currency,
        String customerId,
        String device
    ) {
        RiskAssessment assessment = evaluateRisk.evaluate(new EvaluateRiskCommand(
            merchantId, paymentIntentId, amountMinor, currency, customerId, device
        ));

        return new Decision(assessment.permitsConfirmation(), assessment.assessmentId().value());
    }
}
