package com.paymesh.risk.application;

import com.paymesh.risk.domain.DenylistEntry;
import com.paymesh.risk.domain.DenylistHash;
import com.paymesh.risk.domain.DenylistedEntity;
import com.paymesh.risk.domain.RiskAssessment;
import com.paymesh.risk.domain.RiskOutcome;
import com.paymesh.risk.domain.RiskRuleset;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plain JUnit with in-memory ports. The service does no I/O of its own, so neither does this. */
class EvaluateRiskServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final Duration WINDOW = Duration.ofHours(1);

    private final InMemoryAssessments assessments = new InMemoryAssessments();
    private final InMemoryDenylist denylist = new InMemoryDenylist();
    private final StubVelocity velocity = new StubVelocity();

    private final EvaluateRiskService service =
        new EvaluateRiskService(assessments, denylist, velocity, WINDOW, CLOCK);

    @Test
    void allowsAndRecordsAnOrdinaryPayment() {
        RiskAssessment assessment = service.evaluate(command("cus_1", "device-1", 1_00L));

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.ALLOW);
        assertThat(assessment.permitsConfirmation()).isTrue();
        assertThat(assessments.stored).hasSize(1);
    }

    /** The decision is recorded whatever it is. A block nobody wrote down is a block nobody can explain. */
    @Test
    void recordsTheAssessmentEvenWhenItBlocks() {
        denylist.deny(DenylistedEntity.CUSTOMER, "cus_1");

        RiskAssessment assessment = service.evaluate(command("cus_1", "device-1", 1_00L));

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.BLOCK);
        assertThat(assessment.permitsConfirmation()).isFalse();
        assertThat(assessments.stored).hasSize(1);
    }

    @Test
    void matchesTheDenylistOnDeviceAsWellAsCustomer() {
        denylist.deny(DenylistedEntity.DEVICE, "device-1");

        assertThat(service.evaluate(command("cus_1", "device-1", 1_00L)).outcome())
            .isEqualTo(RiskOutcome.BLOCK);
    }

    /** An expired entry is still a row. It must stop denying anything. */
    @Test
    void ignoresAnExpiredDenylistEntry() {
        denylist.denyUntil(DenylistedEntity.CUSTOMER, "cus_1", NOW.minusSeconds(1));

        assertThat(service.evaluate(command("cus_1", "device-1", 1_00L)).outcome())
            .isEqualTo(RiskOutcome.ALLOW);
    }

    /**
     * A GUEST CHECKOUT IS NOT VELOCITY ZERO BY ACCIDENT. There is no customer to count against, so
     * the lookup is never called -- counting by device instead would look clever and be wrong, since
     * a shared kiosk is one device and many people.
     */
    @Test
    void doesNotCountVelocityForAGuestCheckout() {
        velocity.count = 99;

        RiskAssessment assessment = service.evaluate(command(null, "device-1", 1_00L));

        assertThat(assessment.outcome()).isEqualTo(RiskOutcome.ALLOW);
        assertThat(velocity.calls).as("no customer, so nothing to ask").isZero();
        assertThat(assessment.features().confirmsInWindow()).isZero();
        assertThat(assessment.features().isGuest()).isTrue();
    }

    @Test
    void countsVelocityFromTheStartOfTheWindow() {
        velocity.count = RiskRuleset.VELOCITY_BLOCK_THRESHOLD;

        assertThat(service.evaluate(command("cus_1", null, 1_00L)).outcome())
            .isEqualTo(RiskOutcome.BLOCK);
        assertThat(velocity.since)
            .as("the window is measured back from the decision instant")
            .isEqualTo(NOW.minus(WINDOW));
    }

    /**
     * THE SNAPSHOT IS THE EVIDENCE (SDD 14.6). Everything the rules read has to land on the row, or
     * the decision cannot be reproduced once the rules or the world move on.
     */
    @Test
    void storesTheInputsAndTheRulesetVersionAlongsideTheOutcome() {
        velocity.count = 2;

        RiskAssessment assessment = service.evaluate(command("cus_1", "device-1", 4_200L));

        assertThat(assessment.rulesetVersion()).isEqualTo(RiskRuleset.VERSION);
        assertThat(assessment.features().amountMinor()).isEqualTo(4_200L);
        assertThat(assessment.features().currency()).isEqualTo("INR");
        assertThat(assessment.features().customerId()).isEqualTo("cus_1");
        assertThat(assessment.features().device()).isEqualTo("device-1");
        assertThat(assessment.features().confirmsInWindow()).isEqualTo(2);
        assertThat(assessment.decidedAt()).isEqualTo(NOW);
    }

    /** One merchant's denylist must not deny another merchant's payment. */
    @Test
    void keepsDenylistsPerMerchant() {
        denylist.denyFor(MerchantId.generate(), DenylistedEntity.CUSTOMER, "cus_1");

        assertThat(service.evaluate(command("cus_1", "device-1", 1_00L)).outcome())
            .isEqualTo(RiskOutcome.ALLOW);
    }

    private EvaluateRiskCommand command(String customerId, String device, long amountMinor) {
        return new EvaluateRiskCommand(
            MERCHANT, "pi_" + java.util.UUID.randomUUID(), amountMinor, "INR", customerId, device
        );
    }

    private static final class InMemoryAssessments implements RiskAssessmentRepository {

        private final List<RiskAssessment> stored = new ArrayList<>();

        @Override
        public RiskAssessment append(RiskAssessment assessment) {
            stored.add(assessment);
            return assessment;
        }

        @Override
        public List<RiskAssessment> findRecent(MerchantId merchantId, int limit) {
            return stored.stream()
                .filter(a -> a.merchantId().equals(merchantId))
                .limit(limit)
                .toList();
        }
    }

    private final class InMemoryDenylist implements DenylistRepository {

        private final List<DenylistEntry> entries = new ArrayList<>();

        void deny(DenylistedEntity type, String value) {
            denyFor(MERCHANT, type, value);
        }

        void denyFor(MerchantId merchantId, DenylistedEntity type, String value) {
            entries.add(DenylistEntry.add(merchantId, type, value, "test", NOW.minusSeconds(60), null));
        }

        void denyUntil(DenylistedEntity type, String value, Instant expiresAt) {
            entries.add(DenylistEntry.add(
                MERCHANT, type, value, "test", NOW.minusSeconds(120), expiresAt
            ));
        }

        @Override
        public DenylistEntry add(DenylistEntry entry) {
            entries.add(entry);
            return entry;
        }

        @Override
        public boolean matchesAny(MerchantId merchantId, List<String> hashedValues, Instant now) {
            return entries.stream()
                .filter(e -> e.merchantId().equals(merchantId))
                .filter(e -> e.appliesAt(now))
                .anyMatch(e -> hashedValues.contains(e.hashedValue()));
        }

        @Override
        public Optional<DenylistEntry> find(MerchantId merchantId, String entryId) {
            return entries.stream()
                .filter(e -> e.merchantId().equals(merchantId))
                .filter(e -> e.entryId().value().equals(entryId))
                .findFirst();
        }

        @Override
        public List<DenylistEntry> findByType(
            MerchantId merchantId, DenylistedEntity entityType, int limit
        ) {
            return entries.stream()
                .filter(e -> e.merchantId().equals(merchantId))
                .filter(e -> e.entityType() == entityType)
                .limit(limit)
                .toList();
        }

        @Override
        public boolean remove(MerchantId merchantId, String entryId) {
            return entries.removeIf(e ->
                e.merchantId().equals(merchantId) && e.entryId().value().equals(entryId)
            );
        }
    }

    private static final class StubVelocity implements PaymentVelocityLookup {

        private int count;
        private int calls;
        private Instant since;

        @Override
        public int confirmsSince(MerchantId merchantId, String customerId, Instant since) {
            this.calls++;
            this.since = since;
            return count;
        }
    }

    /** The hash is what the denylist is keyed by, so a drift here silently stops every match. */
    @Test
    void hashesTheSameValueTheSameWayEveryTime() {
        assertThat(DenylistHash.of("cus_1")).isEqualTo(DenylistHash.of(" cus_1 "));
        assertThat(DenylistHash.of("cus_1")).hasSize(64).matches("[0-9a-f]{64}");
    }
}
