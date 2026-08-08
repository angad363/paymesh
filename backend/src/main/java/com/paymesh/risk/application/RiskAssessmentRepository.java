package com.paymesh.risk.application;

import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/**
 * Where decisions are kept. Append-only by contract: there is no update and no delete, because an
 * assessment is evidence (see {@link RiskAssessment}), and V27 backs that with an immutability
 * trigger rather than trusting this interface to be the only writer.
 */
public interface RiskAssessmentRepository {

    RiskAssessment append(RiskAssessment assessment);

    /**
     * One merchant's decisions, newest first. Tenant-scoped like every other read on the platform:
     * an assessment id alone authorizes nothing.
     */
    List<RiskAssessment> findRecent(MerchantId merchantId, int limit);
}
