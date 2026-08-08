package com.paymesh.risk.domain;

/**
 * Everything the rules are allowed to look at, snapshotted at the instant of the decision.
 *
 * <h2>WHY A SNAPSHOT AND NOT A SET OF LOOKUPS</h2>
 *
 * SDD §14.6 requires a historical decision to be reproducible. A rule that re-reads the world
 * cannot be replayed: run it a week later and the velocity count has moved, the customer has been
 * denylisted, the merchant has changed plan. Storing the inputs alongside the outcome is what makes
 * "why did we block this in March?" a question with an answer.
 * <p>
 * So the rules are a pure function of this record, and this record is written to
 * {@code risk_assessments.features} verbatim. If a rule ever needs a fact that is not here, the
 * fact goes here first.
 *
 * @param amountMinor      the intent's amount, in minor units. Never a decimal (ADR-001 in the SDD).
 * @param currency         ISO-4217, uppercase.
 * @param customerId       the customer this intent is for, or null on a guest checkout. Null is a
 *                         signal in its own right rather than missing data.
 * @param device           the opaque client hint the merchant sent on confirm, or null. PayMesh
 *                         does not interpret it; it is matched literally against the denylist.
 * @param confirmsInWindow how many intents this merchant has confirmed for this customer inside the
 *                         velocity window, counted BEFORE this one. Zero for a guest checkout,
 *                         because there is no customer to count against -- not because the count
 *                         was zero, and the two must not be conflated when reading a stored row.
 */
public record RiskFeatures(
    long amountMinor,
    String currency,
    String customerId,
    String device,
    int confirmsInWindow
) {

    public RiskFeatures {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Risk features amount must be positive");
        }

        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Risk features currency cannot be blank");
        }

        if (confirmsInWindow < 0) {
            throw new IllegalArgumentException("Risk features confirm count cannot be negative");
        }

        currency = currency.trim().toUpperCase();
    }

    /** A guest checkout has no customer, so nothing customer-scoped can be said about it. */
    public boolean isGuest() {
        return customerId == null;
    }
}
