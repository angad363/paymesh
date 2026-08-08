package com.paymesh.risk.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules, driven directly. No context, no database: the whole reason the ruleset is a pure
 * function of {@link RiskFeatures} is that its behaviour can be pinned like this.
 */
class RiskRulesetTest {

    @Test
    void allowsAnOrdinaryPayment() {
        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(features(1_00L, 0), false);

        assertThat(verdict.outcome()).isEqualTo(RiskOutcome.ALLOW);
        assertThat(verdict.matchedRules()).isEmpty();
    }

    @Test
    void blocksADenylistedPayment() {
        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(features(1_00L, 0), true);

        assertThat(verdict.outcome()).isEqualTo(RiskOutcome.BLOCK);
        assertThat(verdict.matchedRules()).containsExactly("DENYLISTED");
    }

    @Test
    void reviewsALargePaymentWithoutBlockingIt() {
        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(
            features(RiskRuleset.LARGE_AMOUNT_MINOR, 0), false
        );

        assertThat(verdict.outcome()).isEqualTo(RiskOutcome.REVIEW);
        assertThat(verdict.matchedRules()).containsExactly("LARGE_AMOUNT");
    }

    @Test
    void reviewsAtTheVelocityThresholdAndBlocksAtTheHigherOne() {
        assertThat(RiskRuleset.evaluate(
            features(1_00L, RiskRuleset.VELOCITY_REVIEW_THRESHOLD), false
        ).outcome()).isEqualTo(RiskOutcome.REVIEW);

        assertThat(RiskRuleset.evaluate(
            features(1_00L, RiskRuleset.VELOCITY_BLOCK_THRESHOLD), false
        ).outcome()).isEqualTo(RiskOutcome.BLOCK);
    }

    /** One below each threshold is not a match. Off-by-one here is a false positive on real money. */
    @Test
    void doesNotFireJustBelowEitherVelocityThreshold() {
        assertThat(RiskRuleset.evaluate(
            features(1_00L, RiskRuleset.VELOCITY_REVIEW_THRESHOLD - 1), false
        ).matchedRules()).isEmpty();

        assertThat(RiskRuleset.evaluate(
            features(1_00L, RiskRuleset.VELOCITY_BLOCK_THRESHOLD - 1), false
        ).outcome())
            .as("still only a review, because the block threshold has not been reached")
            .isEqualTo(RiskOutcome.REVIEW);
    }

    /**
     * THE EVIDENCE IS THE PRODUCT, NOT THE VERDICT. A blocked payment must still name every reason
     * it was blocked, because "blocked, and here are the three things wrong with it" is a different
     * conversation with a merchant than "blocked".
     */
    @Test
    void reportsEveryMatchedRuleRatherThanStoppingAtTheFirstBlock() {
        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(
            features(RiskRuleset.LARGE_AMOUNT_MINOR, RiskRuleset.VELOCITY_BLOCK_THRESHOLD), true
        );

        assertThat(verdict.outcome()).isEqualTo(RiskOutcome.BLOCK);
        assertThat(verdict.matchedRules())
            .containsExactly("DENYLISTED", "VELOCITY_BLOCK", "LARGE_AMOUNT");
    }

    /** A review must never quietly downgrade a block that fired before it. */
    @Test
    void keepsTheWorstOutcomeWhenALaterRuleIsMilder() {
        RiskRuleset.Verdict verdict = RiskRuleset.evaluate(
            features(RiskRuleset.LARGE_AMOUNT_MINOR, 0), true
        );

        assertThat(verdict.outcome()).isEqualTo(RiskOutcome.BLOCK);
    }

    /**
     * BLOCK is the only outcome that stops a payment. Pinned here because {@code RiskOutcome} is
     * ordered by severity and {@code RiskRuleset.worse} depends on that ordering -- reordering the
     * enum would otherwise silently invert every decision.
     */
    @Test
    void onlyBlockRefusesConfirmation() {
        assertThat(RiskOutcome.ALLOW.permitsConfirmation()).isTrue();
        assertThat(RiskOutcome.REVIEW.permitsConfirmation()).isTrue();
        assertThat(RiskOutcome.BLOCK.permitsConfirmation()).isFalse();

        assertThat(RiskOutcome.BLOCK.ordinal())
            .as("severity ordering is load-bearing for RiskRuleset.worse")
            .isGreaterThan(RiskOutcome.REVIEW.ordinal());
        assertThat(RiskOutcome.REVIEW.ordinal()).isGreaterThan(RiskOutcome.ALLOW.ordinal());
    }

    private static RiskFeatures features(long amountMinor, int confirmsInWindow) {
        return new RiskFeatures(amountMinor, "INR", "cus_x", "device-1", confirmsInWindow);
    }
}
