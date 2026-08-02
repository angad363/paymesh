package com.paymesh.simulator.domain;

import java.time.Instant;

/**
 * The body of one callback, field for field as PayMesh's {@code ProviderCallbackRequest} declares
 * it. <b>The published contract, restated rather than imported</b> -- see {@link SimulatedOutcome}
 * for why that is deliberate.
 *
 * <h2>Two details that fail silently if they are wrong</h2>
 *
 * Neither produces an error. Both produce an {@code IGNORED_TERMINAL} against a payment that looks
 * like it should have moved, which is the hardest kind of bug to find in this direction.
 * <ul>
 *   <li><b>{@code SUCCEEDED} must carry {@code capturedAmountMinor} equal to the intent's amount,
 *       and {@code AUTHORIZED} must carry {@code authorizedAmountMinor} equal to it.</b>
 *       {@code RecordProviderCallbackService} refuses a claimed amount the intent does not
 *       authorize -- a provider does not get to change what is owed (SDD 12.3) -- and records the
 *       refusal rather than the payment.</li>
 *   <li><b>{@code FAILED} and {@code REQUIRES_ACTION} carry no amount at all.</b> Nothing was
 *       collected and nothing was held, so an amount there is noise in a durable audit record.</li>
 * </ul>
 *
 * <p>
 * {@code paymentIntentId} carries the stored {@code callbackReference} -- the caller's own string,
 * echoed back. The simulator does not know that PayMesh calls it a payment intent id; it knows only
 * that this is the field the receiver reads it from.
 */
public record CallbackBody(
    String eventId,
    Instant occurredAt,
    String paymentIntentId,
    String providerReference,
    SimulatedOutcome outcome,
    Long authorizedAmountMinor,
    Long capturedAmountMinor,
    String failureCode,
    String failureMessage,
    String actionUrl
) {

    /**
     * A 3DS challenge URL with a token in its query string.
     * <p>
     * The token is there on purpose: PayMesh redacts a URL's query before storing it (SDD 12.6), and
     * a simulator that only ever sent clean URLs would let that redaction rot untested from the one
     * side that can actually exercise it end to end.
     */
    public static final String CHALLENGE_URL =
        "https://3ds.simulator.test/challenge?token=SIMULATED-CHALLENGE-TOKEN";

    public CallbackBody {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("A callback must carry an event identifier");
        }

        if (occurredAt == null || outcome == null) {
            throw new IllegalArgumentException("A callback must carry a timestamp and an outcome");
        }

        if (paymentIntentId == null || paymentIntentId.isBlank()) {
            throw new IllegalArgumentException("A callback must name the caller's reference");
        }
    }

    /** Collected: the captured amount is checked against the intent's own figure. */
    public static CallbackBody succeeded(
        String eventId,
        String callbackReference,
        SimulatedPaymentId providerPaymentId,
        long capturedAmountMinor,
        Instant occurredAt
    ) {
        return new CallbackBody(
            eventId, occurredAt, callbackReference, providerPaymentId.value(),
            SimulatedOutcome.SUCCEEDED, null, capturedAmountMinor, null, null, null
        );
    }

    /** Held, not moved. The authorized amount is the one checked. */
    public static CallbackBody authorized(
        String eventId,
        String callbackReference,
        SimulatedPaymentId providerPaymentId,
        long authorizedAmountMinor,
        Instant occurredAt
    ) {
        return new CallbackBody(
            eventId, occurredAt, callbackReference, providerPaymentId.value(),
            SimulatedOutcome.AUTHORIZED, authorizedAmountMinor, null, null, null, null
        );
    }

    /** Refused. No amount -- nothing moved. */
    public static CallbackBody failed(
        String eventId,
        String callbackReference,
        SimulatedPaymentId providerPaymentId,
        String failureCode,
        String failureMessage,
        Instant occurredAt
    ) {
        return new CallbackBody(
            eventId, occurredAt, callbackReference, providerPaymentId.value(),
            SimulatedOutcome.FAILED, null, null, failureCode, failureMessage, null
        );
    }

    /** An off-site step. No amount, and a URL whose query PayMesh is expected to drop. */
    public static CallbackBody requiresAction(
        String eventId,
        String callbackReference,
        SimulatedPaymentId providerPaymentId,
        Instant occurredAt
    ) {
        return new CallbackBody(
            eventId, occurredAt, callbackReference, providerPaymentId.value(),
            SimulatedOutcome.REQUIRES_ACTION, null, null, null, null, CHALLENGE_URL
        );
    }

    /** The same event, restamped. Used to build the out-of-order pair. */
    public CallbackBody at(String newEventId, Instant newOccurredAt) {
        return new CallbackBody(
            newEventId, newOccurredAt, paymentIntentId, providerReference, outcome,
            authorizedAmountMinor, capturedAmountMinor, failureCode, failureMessage, actionUrl
        );
    }
}
