package com.paymesh.refund.domain;

/**
 * What PayMesh did with a callback, as opposed to what the callback said.
 * <p>
 * Returned to the provider so a well-behaved one can tell "we took this" from "we already had it"
 * without diffing state. All three are a 200: a duplicate is not the provider's error, and neither
 * is a late delivery -- answering 4xx would make a correct provider retry forever.
 */
public enum RefundCallbackOutcome {

    /** Recorded, and the refund moved. */
    APPLIED,

    /** Already seen, by {@code (provider, external_event_id)}. Nothing changed, and nothing should. */
    DUPLICATE,

    /** Older than what has already been applied. Refused on purpose; see ADR-012's ordering rule. */
    STALE,

    /** A new event for a refund already in a terminal state. Recorded, not applied. */
    NOT_APPLICABLE
}
