package com.paymesh.risk.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A row of {@code risk_assessments}.
 * <p>
 * {@code @Immutable} because the table is: V27 refuses UPDATE and DELETE by trigger. The annotation
 * is not the guard -- it stops Hibernate generating an UPDATE at all, so a dirty-checked change
 * fails at the mapper rather than surfacing as a trigger exception nobody expected.
 */
@Entity
@Immutable
@Table(name = "risk_assessments")
public class RiskAssessmentJpaEntity {

    @Id
    @Column(name = "assessment_id", nullable = false, updatable = false, length = 40)
    private String assessmentId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "payment_intent_id", nullable = false, updatable = false, length = 40)
    private String paymentIntentId;

    @Column(name = "outcome", nullable = false, updatable = false, length = 16)
    private String outcome;

    // A JSON array of rule names. ck_risk_assessments_matched_rules_shape keeps it an array, so
    // this cannot meet the object-into-List failure V26's header describes for orders.metadata.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_rules", nullable = false, updatable = false)
    private List<String> matchedRules;

    @Column(name = "ruleset_version", nullable = false, updatable = false)
    private int rulesetVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "features", nullable = false, updatable = false)
    private Map<String, Object> features;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected RiskAssessmentJpaEntity() {
    }

    public RiskAssessmentJpaEntity(
        String assessmentId,
        String merchantId,
        String paymentIntentId,
        String outcome,
        List<String> matchedRules,
        int rulesetVersion,
        Map<String, Object> features,
        Instant decidedAt
    ) {
        this.assessmentId = assessmentId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.outcome = outcome;
        this.matchedRules = matchedRules;
        this.rulesetVersion = rulesetVersion;
        this.features = features;
        this.decidedAt = decidedAt;
    }

    public String assessmentId() {
        return assessmentId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }

    public String outcome() {
        return outcome;
    }

    public List<String> matchedRules() {
        return matchedRules;
    }

    public int rulesetVersion() {
        return rulesetVersion;
    }

    public Map<String, Object> features() {
        return features;
    }

    public Instant decidedAt() {
        return decidedAt;
    }
}
