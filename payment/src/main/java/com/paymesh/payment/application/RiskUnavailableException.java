package com.paymesh.payment.application;

/**
 * Risk could not be reached at all -- a timeout, a connection refusal, or the circuit breaker
 * already open -- as distinct from {@link PaymentBlockedByRiskException}, which means Risk WAS
 * reached and said no (ADR-044 section 5).
 *
 * <h2>WHY THIS IS A SEPARATE EXCEPTION AND NOT A REUSE OF THE BLOCK</h2>
 *
 * Conflating the two would make an unreachable dependency indistinguishable from a real risk
 * refusal at the API boundary. A merchant seeing {@code PAYMENT_BLOCKED_BY_RISK} for a network
 * blip would look for a rule that never fired; a merchant seeing a plain 5xx for a genuine
 * denylist hit would retry a confirm that is going to fail again. {@link PaymentExceptionHandler}
 * maps this to 503 (retryable), the block to 422 (not).
 *
 * <p>Thrown only from the CLOSED tier of {@code RiskUnavailablePolicy} -- an amount at or above the
 * configured threshold, with the risk service unreachable, refuses rather than guesses. The OPEN
 * tier never throws this: it records the fallback decision and returns a permitted {@code
 * Decision} instead, so a small payment does not hang behind an outage it can afford to risk.
 */
public class RiskUnavailableException extends RuntimeException {

    private final String paymentIntentId;

    public RiskUnavailableException(String paymentIntentId, String reason) {
        super("Risk service unreachable for payment intent " + paymentIntentId + ": " + reason);
        this.paymentIntentId = paymentIntentId;
    }

    public String paymentIntentId() {
        return paymentIntentId;
    }
}
