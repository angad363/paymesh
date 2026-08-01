package com.paymesh.payment.domain;

/**
 * What PayMesh DID about a callback, which is not what the callback said (see
 * {@link ProviderOutcome} for that).
 * <p>
 * Every one of these is answered with {@code 200}. A provider retries on any non-2xx, so answering a
 * duplicate or a superseded event with a conflict produces an infinite retry loop against a payment
 * that is already finished -- a self-inflicted outage that looks like a provider problem (ADR-012).
 * The one non-2xx the callback endpoint has is the {@code 404} for an intent this platform does not
 * know, and there a retry is exactly what should happen.
 */
public enum ProviderCallbackOutcome {

    /** The intent moved. Exactly one state-history row and one outbox event were written. */
    APPLIED,

    /**
     * Superseded: the event's {@code occurredAt} was not strictly after the last one already applied
     * to this intent. Nothing moved, and the row was still stored so a re-delivery is deduplicated.
     */
    IGNORED_STALE,

    /**
     * The transition is not legal from the intent's current state -- most often because the intent
     * is already SUCCEEDED, FAILED or CANCELLED -- or the event claimed an amount the intent does not
     * authorize. Nothing moved.
     * <p>
     * <b>These rows are the ones reconciliation will look for.</b> An IGNORED_TERMINAL SUCCEEDED
     * against a CANCELLED intent is the record of a genuine PayMesh/provider divergence: the merchant
     * gave up on a 3DS challenge the customer then completed. Nothing here resolves it, and the row
     * is the only trace that there is anything to resolve.
     */
    IGNORED_TERMINAL,

    /**
     * The same provider event, delivered again. Nothing happened at all.
     * <p>
     * <b>Never stored, and {@code ck_provider_callbacks_outcome} does not permit it.</b> By the time
     * this is known the transaction is rolling back on the primary key, and the row that already
     * exists -- with whatever outcome the first delivery earned -- is the record.
     */
    DUPLICATE
}
