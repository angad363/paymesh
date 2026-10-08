package com.paymesh.risk.api;

import com.paymesh.risk.application.EvaluateRiskCommand;
import com.paymesh.risk.application.EvaluateRiskService;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * THE ONE ENDPOINT THIS DEPLOYABLE HAS (ADR-044). Payment's confirm calls this synchronously,
 * across the network, in place of the in-process {@code RiskModuleCheck} call the monolith still
 * makes on the dual path.
 *
 * <h2>{@code /internal/**}, not {@code /api/**}</h2>
 *
 * This is never a merchant-facing route: no merchant token reaches it, no merchant documentation
 * names it, and the gateway forwards {@code /internal/v1/risk-evaluations/**} unrate-limited, the
 * same treatment {@code /internal/v1/notifications/**} gets (ADR-043) -- a provider or, here, a
 * sibling service retrying a call it is entitled to retry must not be throttled into a failure on
 * the money path. Authentication is {@link com.paymesh.risk.infrastructure.security.RiskEvaluationKeyFilter},
 * not a JWT: see that class's javadoc for why.
 *
 * <h2>WHY THIS RUNS EvaluateRiskService UNCHANGED</h2>
 *
 * The service, the ruleset and the assessment repository are byte-for-byte the same classes the
 * monolith's in-process {@code RiskModuleCheck} calls. Extraction moved the transport, not the
 * decision -- ADR-030's "Risk decides, Payment acts" split is exactly as true over HTTP as it was
 * over a method call.
 */
@RestController
@RequestMapping("internal/v1/risk-evaluations")
public final class RiskEvaluationController {

    private final EvaluateRiskService evaluateRisk;

    public RiskEvaluationController(EvaluateRiskService evaluateRisk) {
        this.evaluateRisk = evaluateRisk;
    }

    @PostMapping
    RiskDecisionResponse evaluate(@Valid @RequestBody EvaluateRiskRequest request) {
        RiskAssessment assessment = evaluateRisk.evaluate(new EvaluateRiskCommand(
            MerchantId.from(request.merchantId()),
            request.paymentIntentId(),
            request.amountMinor(),
            request.currency(),
            request.customerId(),
            request.device()
        ));

        return RiskDecisionResponse.from(assessment);
    }
}
