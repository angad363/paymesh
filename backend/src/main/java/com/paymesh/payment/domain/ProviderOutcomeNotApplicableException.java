package com.paymesh.payment.domain;

/**
 * Raised when a provider callback asks for a transition the intent's current state cannot make.
 * <p>
 * <b>It is not an error condition for the caller.</b> The callback path catches it and answers
 * {@code 200} with an outcome of {@code IGNORED_TERMINAL}, because a provider retries on any
 * non-2xx and a conflict returned for a superseded event produces an infinite retry loop against a
 * payment that is already finished (ADR-012). The exception exists so the state machine has exactly
 * one implementation -- the aggregate's -- rather than a second copy in the service deciding what is
 * legal.
 * <p>
 * It lives in the domain because the aggregate is what refuses. It carries no HTTP status.
 */
public class ProviderOutcomeNotApplicableException extends RuntimeException {

    public ProviderOutcomeNotApplicableException(
        PaymentIntentId paymentIntentId,
        PaymentIntentStatus status,
        PaymentIntentStatus requested
    ) {
        super("Payment intent " + paymentIntentId.value() + " cannot move to " + requested.name()
            + " while it is " + status.name());
    }
}
