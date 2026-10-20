package com.paymesh.payment.application;

/**
 * Risk refused this confirm.
 *
 * <h2>WHY THIS IS NOT A FAILED PAYMENT</h2>
 *
 * The intent stays in {@code REQUIRES_CONFIRMATION} and remains confirmable. A denylist entry is a
 * live opinion an operator can retract -- entries even carry an expiry for exactly that reason --
 * so burning the intent for a decision that may be reversed in a minute is the harsher of the two
 * available defaults. The merchant retries and it works, rather than the merchant creating a second
 * intent against an order whose slot the first one was holding.
 * <p>
 * The cost, stated: a payment PayMesh is confident about stays live and keeps occupying
 * {@code uq_payment_intents_live_per_order} until it expires or is cancelled. The abandoned-intent
 * sweep already frees exactly that, which is why this is affordable.
 * <p>
 * <b>Carries no reason.</b> See {@link RiskCheck} -- naming the rule that refused is an oracle.
 */
public class PaymentBlockedByRiskException extends RuntimeException {

    private final String assessmentId;

    public PaymentBlockedByRiskException(String assessmentId) {
        super("Payment was refused by risk evaluation " + assessmentId);
        this.assessmentId = assessmentId;
    }

    /** For support, and for the response body's correlation field. Never the rules. */
    public String assessmentId() {
        return assessmentId;
    }
}
