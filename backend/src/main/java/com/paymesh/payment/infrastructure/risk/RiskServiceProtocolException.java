package com.paymesh.payment.infrastructure.risk;

/**
 * Risk WAS reached and the exchange completed, but what came back is not a decision this client can
 * act on -- a 4xx (a bad request or a rejected key: our side is misconfigured or buggy), or a 2xx
 * whose body is missing the {@code permitted} verdict (a contract violation). ADR-044.
 *
 * <h2>WHY THIS IS NOT ROUTED TO THE FALLBACK</h2>
 *
 * {@link RiskUnavailablePolicy} exists for one thing: Risk could not be reached, so nobody looked
 * at this payment. That is a transient, self-healing condition, and failing open on a small amount
 * is the right trade for it. A 4xx or a malformed body is NONE of those things -- it does not
 * self-heal, and absorbing it as "unavailable" would silently bypass risk on every sub-threshold
 * confirm for as long as the misconfiguration lasts, logging only a WARN that says "transport
 * failure" about a problem that is not transport at all. So {@link RiskServiceHttpCheck} lets this
 * propagate and the confirm fails loudly (a 500) -- the signal that makes someone fix the key or
 * the contract, rather than a quiet, indefinite risk bypass.
 *
 * <p>The circuit breaker {@code ignoreExceptions} this type for the same reason: a persistent 4xx
 * must not be able to trip the breaker into the fast-fail-then-fallback path either.
 */
public final class RiskServiceProtocolException extends RuntimeException {

    public RiskServiceProtocolException(String message) {
        super(message);
    }
}
