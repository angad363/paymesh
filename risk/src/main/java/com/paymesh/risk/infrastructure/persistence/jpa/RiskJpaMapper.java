package com.paymesh.risk.infrastructure.persistence.jpa;

import com.paymesh.risk.domain.DenylistEntry;
import com.paymesh.risk.domain.DenylistEntryId;
import com.paymesh.risk.domain.DenylistedEntity;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.risk.domain.RiskAssessmentId;
import com.paymesh.risk.domain.RiskFeatures;
import com.paymesh.risk.domain.RiskOutcome;
import com.paymesh.shared.tenant.MerchantId;

import java.util.HashMap;
import java.util.Map;

/**
 * Hand-written translation between Risk's aggregates and its rows (ADR-004).
 * <p>
 * Enums cross as their {@code name()}, never their ordinal, for the reason the simulator's mapper
 * gives: an ordinal is positional, so inserting a value into the middle of an enum silently
 * rewrites the meaning of every stored row -- and the CHECK constraints in V27/V28 are written
 * against the names anyway.
 */
final class RiskJpaMapper {

    /**
     * The keys of the feature snapshot, written once here and read once below.
     * <p>
     * THESE ARE A STORED FORMAT, not an implementation detail. A row written today must still be
     * readable after {@link RiskFeatures} gains a field, so renaming one of these is a migration
     * rather than a refactor -- which is exactly the property that makes an old decision
     * reproducible (SDD §14.6).
     */
    private static final String AMOUNT = "amountMinor";
    private static final String CURRENCY = "currency";
    private static final String CUSTOMER = "customerId";
    private static final String DEVICE = "device";
    private static final String INTENTS = "intentsInWindow";

    private RiskJpaMapper() {
    }

    static RiskAssessmentJpaEntity toEntity(RiskAssessment assessment) {
        RiskFeatures features = assessment.features();

        // HashMap rather than Map.of: the snapshot legitimately contains nulls (a guest checkout
        // has no customer), and Map.of throws on a null value.
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put(AMOUNT, features.amountMinor());
        snapshot.put(CURRENCY, features.currency());
        snapshot.put(CUSTOMER, features.customerId());
        snapshot.put(DEVICE, features.device());
        snapshot.put(INTENTS, features.intentsInWindow());

        return new RiskAssessmentJpaEntity(
            assessment.assessmentId().value(),
            assessment.merchantId().value(),
            assessment.paymentIntentId(),
            assessment.outcome().name(),
            assessment.matchedRules(),
            assessment.rulesetVersion(),
            snapshot,
            assessment.decidedAt()
        );
    }

    static RiskAssessment toDomain(RiskAssessmentJpaEntity entity) {
        Map<String, Object> snapshot = entity.features();

        return new RiskAssessment(
            RiskAssessmentId.from(entity.assessmentId()),
            MerchantId.from(entity.merchantId()),
            entity.paymentIntentId(),
            RiskOutcome.valueOf(entity.outcome()),
            entity.matchedRules(),
            entity.rulesetVersion(),
            new RiskFeatures(
                number(snapshot.get(AMOUNT)).longValue(),
                (String) snapshot.get(CURRENCY),
                (String) snapshot.get(CUSTOMER),
                (String) snapshot.get(DEVICE),
                number(snapshot.get(INTENTS)).intValue()
            ),
            entity.decidedAt()
        );
    }

    /**
     * JSON numbers come back as whatever Jackson chose on the way in -- Integer for a small count,
     * Long for an amount that did not fit. Reading either as a fixed type is a ClassCastException
     * waiting for the first row that crosses the boundary, so both go through Number.
     */
    private static Number number(Object value) {
        if (value instanceof Number parsed) {
            return parsed;
        }

        throw new IllegalArgumentException(
            "Risk feature snapshot holds a non-numeric value where a number was stored: " + value
        );
    }

    static DenylistEntryJpaEntity toEntity(DenylistEntry entry) {
        return new DenylistEntryJpaEntity(
            entry.entryId().value(),
            entry.merchantId().value(),
            entry.entityType().name(),
            entry.hashedValue(),
            entry.reason(),
            entry.createdAt(),
            entry.expiresAt()
        );
    }

    static DenylistEntry toDomain(DenylistEntryJpaEntity entity) {
        return new DenylistEntry(
            DenylistEntryId.from(entity.entryId()),
            MerchantId.from(entity.merchantId()),
            DenylistedEntity.valueOf(entity.entityType()),
            entity.hashedValue(),
            entity.reason(),
            entity.createdAt(),
            entity.expiresAt()
        );
    }
}
