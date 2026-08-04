package com.paymesh.reconciliation.application;

import java.time.Instant;

/**
 * This module's way into Payment: replay one provider outcome as if it had arrived as a callback.
 *
 * <h2>Why it is a REPLAY and not a repair instruction</h2>
 *
 * The obvious port would be {@code markSucceeded(intentId, amount)}. That would be a second way to
 * move a payment, reachable by a scheduled job, bypassing the amount check, the staleness guard, the
 * terminal-state refusal, the attempt row and the outbox event. SDD 12.3's rule that a provider does
 * not get to change what is owed is enforced in exactly one place, and a job with its own entrance
 * would be outside it.
 * <p>
 * So this port hands over the provider's claim and nothing else. The adapter builds the same
 * {@code ProviderEvent} the HTTP boundary builds and calls the same service, and the aggregate
 * decides. {@code RecordProviderCallbackService}'s own javadoc has anticipated this caller since
 * ADR-015.
 *
 * @see RepairOutcome
 */
public interface PaymentRepair {

    /**
     * @param eventId    the deduplication key, WITH the provider name. Must be <b>deterministic per
     *                   (row, terminal state)</b> and distinct from anything the provider itself
     *                   sends -- see {@link ReconcileProviderDayService} for why both halves matter.
     *                   Re-running a day must be a duplicate, not a second application.
     * @param occurredAt the PROVIDER's timestamp for the outcome, never the job's clock. It is the
     *                   value the monotonic guard compares, so stamping it with "now" would let a
     *                   reconciliation of an old day overwrite a newer callback.
     */
    RepairOutcome replay(
        String paymentIntentId,
        String providerReference,
        ReplayedOutcome outcome,
        Long authorizedAmountMinor,
        Long capturedAmountMinor,
        String failureCode,
        String failureMessage,
        String eventId,
        Instant occurredAt
    );
}
