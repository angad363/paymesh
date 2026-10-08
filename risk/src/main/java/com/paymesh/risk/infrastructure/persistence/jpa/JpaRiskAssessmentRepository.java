package com.paymesh.risk.infrastructure.persistence.jpa;

import com.paymesh.risk.application.RiskAssessmentRepository;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.Limit;

import java.util.List;

/** PostgreSQL-backed RiskAssessmentRepository. Append and read; the table refuses anything else. */
public final class JpaRiskAssessmentRepository implements RiskAssessmentRepository {

    private final SpringDataRiskAssessmentRepository assessments;

    public JpaRiskAssessmentRepository(SpringDataRiskAssessmentRepository assessments) {
        this.assessments = assessments;
    }

    @Override
    public RiskAssessment append(RiskAssessment assessment) {
        assessments.save(RiskJpaMapper.toEntity(assessment));

        // The stored row is returned rather than re-read: the entity is @Immutable and the table
        // refuses updates, so there is nothing a round trip could tell us that we do not know.
        return assessment;
    }

    @Override
    public List<RiskAssessment> findRecent(MerchantId merchantId, int limit) {
        return assessments
            .findByMerchantIdOrderByDecidedAtDesc(merchantId.value(), Limit.of(limit))
            .stream()
            .map(RiskJpaMapper::toDomain)
            .toList();
    }
}
