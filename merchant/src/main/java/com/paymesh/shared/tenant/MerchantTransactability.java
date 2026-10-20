package com.paymesh.shared.tenant;

/**
 * The platform's verdict on whether a merchant may perform an authenticated write, read from the
 * event-fed {@code merchant_ref} projection (ADR-039).
 *
 * <h2>Three outcomes, not a boolean, because the projection lags</h2>
 *
 * Before ADR-039 the gate read the authoritative {@code merchants} table, which was always current,
 * so the only question was allowed-or-not. The projection is eventually consistent -- and in the
 * monolith the relay is asynchronous and off under {@code dev}, so a just-registered merchant is
 * routinely absent for a relay cycle. That absence is NOT a denial; it is "ask again shortly".
 * Collapsing it into {@code DENIED} would turn propagation lag into a permanent 403 for a merchant
 * that is in fact fine.
 *
 * <p>Note the one lag this does NOT catch: a just-<em>activated</em> merchant is already present
 * (from {@code merchant.registered}) with its prior status, so it reads as {@code DENIED} (a brief
 * 403), not {@code UNKNOWN}, until {@code merchant.activated} propagates. Status alone cannot tell
 * "genuinely pending" from "activated-but-lagging"; that one-relay-cycle window is the accepted
 * ceiling of ADR-039 section 4.
 */
public enum MerchantTransactability {

    /** Present in the projection with status {@code ACTIVE}. The write proceeds. */
    ALLOWED,

    /**
     * Present in the projection, but not {@code ACTIVE} (suspended, closed, or still unverified).
     * A permanent refusal for now -- the caller's next step is to contact the platform. Maps to 403.
     */
    DENIED,

    /**
     * Absent from the projection: the merchant's lifecycle event has not propagated yet. A transient,
     * retryable state -- maps to 503, not 403 and never 500. Only authenticated callers reach the
     * gate and a valid token always names a real merchant, so for a legitimate caller this is always
     * lag, never a probe.
     */
    UNKNOWN
}
