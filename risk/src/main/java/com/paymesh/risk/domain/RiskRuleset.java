package com.paymesh.risk.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * The rules, as code, at a stated version.
 *
 * <h2>WHY THIS IS NOT A TABLE OF STORED EXPRESSIONS</h2>
 *
 * The plan called for {@code risk_rules} holding versioned expressions. That means writing or
 * embedding an evaluator, plus a syntax, plus a way to test an expression before it goes live, plus
 * the failure mode where a bad expression takes the money path down at runtime rather than at
 * compile time. What it buys is one thing: <b>changing a rule without a deploy</b>. Nobody is
 * asking for that, and every rule PayMesh needs today is a boolean over {@link RiskFeatures} that
 * a compiler can check.
 * <p>
 * What SDD §14.6 actually requires is <em>reproducibility</em> -- being able to answer "why did we
 * block this in March?". That needs the inputs and the rule version stored with the decision, which
 * is exactly what {@link RiskAssessment} does. It does not need the rule to have been data.
 * <p>
 * <b>The upgrade path, when a non-engineer needs to tune a threshold:</b> the thresholds move to
 * configuration first (a bound properties record), which covers most of the real demand at a
 * fraction of the cost. A stored expression language is the answer only when someone needs to add
 * a rule <em>shape</em> nobody anticipated, and that is a different and much later problem.
 *
 * <h2>The version is the contract</h2>
 *
 * {@link #VERSION} is written into every assessment row. <b>Bump it whenever the behaviour of any
 * rule below changes</b> -- a new rule, a removed rule, a moved threshold. A stored decision then
 * names the exact logic that produced it, and two rows with different versions are not comparable
 * evidence. Not bumping it is the one way to make the audit trail lie.
 */
public final class RiskRuleset {

    /**
     * Version 1: the three rules below.
     * <p>
     * Bump on ANY behavioural change to them. This is stamped on every stored assessment.
     */
    public static final int VERSION = 1;

    /**
     * Above this, a payment is worth a second look. Deliberately generous: this exists to catch the
     * outlier, and a threshold that fires on ordinary traffic trains operators to ignore it.
     */
    public static final long LARGE_AMOUNT_MINOR = 5_000_00L;

    /** Confirms for one customer inside the window before the pattern stops looking like shopping. */
    public static final int VELOCITY_REVIEW_THRESHOLD = 3;

    /** And the point at which it stops looking like a human at all. */
    public static final int VELOCITY_BLOCK_THRESHOLD = 10;

    private RiskRuleset() {
    }

    /**
     * Runs every rule and returns the worst outcome any of them reached, with the names of the ones
     * that matched.
     * <p>
     * EVERY rule runs even once one has blocked, rather than short-circuiting. The evidence is the
     * product here, not the verdict: "blocked, and here are the four things wrong with it" is a
     * different conversation with a merchant than "blocked by rule 1".
     *
     * @param denylisted whether the denylist already matched this payment. Passed in rather than
     *                   looked up, because a rule that reads the database is a rule that cannot be
     *                   replayed -- see {@link RiskFeatures}.
     */
    public static Verdict evaluate(RiskFeatures features, boolean denylisted) {
        if (features == null) {
            throw new IllegalArgumentException("Risk features cannot be null");
        }

        List<String> matched = new ArrayList<>();
        RiskOutcome worst = RiskOutcome.ALLOW;

        if (denylisted) {
            matched.add("DENYLISTED");
            worst = RiskOutcome.BLOCK;
        }

        // Guests are not counted, so this cannot fire on a guest checkout -- see RiskFeatures.
        if (features.intentsInWindow() >= VELOCITY_BLOCK_THRESHOLD) {
            matched.add("VELOCITY_BLOCK");
            worst = RiskOutcome.BLOCK;
        } else if (features.intentsInWindow() >= VELOCITY_REVIEW_THRESHOLD) {
            matched.add("VELOCITY_REVIEW");
            worst = worse(worst, RiskOutcome.REVIEW);
        }

        if (features.amountMinor() >= LARGE_AMOUNT_MINOR) {
            matched.add("LARGE_AMOUNT");
            worst = worse(worst, RiskOutcome.REVIEW);
        }

        return new Verdict(worst, List.copyOf(matched));
    }

    /**
     * BLOCK beats REVIEW beats ALLOW. Ordinal order, asserted rather than assumed, because a
     * reordering of {@link RiskOutcome} would otherwise silently invert the severity of every
     * decision this class makes.
     */
    private static RiskOutcome worse(RiskOutcome left, RiskOutcome right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }

    /**
     * @param matchedRules the rules that fired, in evaluation order. Empty on a clean ALLOW, and
     *                     stored as-is so a decision names its own reasons.
     */
    public record Verdict(RiskOutcome outcome, List<String> matchedRules) {

        public Verdict {
            if (outcome == null) {
                throw new IllegalArgumentException("Risk verdict outcome cannot be null");
            }

            matchedRules = List.copyOf(matchedRules == null ? List.of() : matchedRules);
        }
    }
}
