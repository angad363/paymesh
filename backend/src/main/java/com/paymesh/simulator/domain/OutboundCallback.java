package com.paymesh.simulator.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One callback the provider intends to deliver, and the reason this module exists.
 *
 * <h2>Why a row rather than an HTTP call</h2>
 *
 * An inline POST from the create handler would be shorter and would express none of the failure
 * modes SDD 13 asks for, because all of them are properties of <em>when</em> and <em>how often</em>
 * a callback is delivered rather than of its contents:
 * <ul>
 *   <li><b>delayed</b> -- {@link #deliverAfter()} in the future</li>
 *   <li><b>lost</b> -- no row is written at all ({@link SimulatedBehaviour#TIMEOUT})</li>
 *   <li><b>duplicate</b> -- two rows sharing one {@link #externalEventId()}</li>
 *   <li><b>out of order</b> -- a row whose {@link #occurredAt()} is earlier than a delivered one</li>
 *   <li><b>retried</b> -- {@link #status()} stays PENDING and {@link #attempts()} climbs</li>
 * </ul>
 *
 * <h2>Two clocks, and they are not the same clock</h2>
 *
 * {@link #occurredAt()} is the PROVIDER'S EVENT CLOCK -- the value PayMesh's monotonic ordering
 * guard compares against {@code payment_attempts.last_provider_event_at}. It is fixed when the row
 * is written.
 * <p>
 * The signature's {@code t=} stamp is a FRESHNESS stamp and is taken at DELIVERY time, in the
 * dispatcher. Conflating them makes a deliberately delayed callback arrive carrying a signature
 * older than the receiver's ±300s window and earn a 401 that reads like a receiver bug.
 *
 * <h2>The body is stored, not rebuilt</h2>
 *
 * {@link #body()} holds the exact JSON string to send, serialized once here. The dispatcher signs
 * that string and posts that string, so there is no serialization step in between for the signed
 * bytes and the sent bytes to drift across. Re-serializing after signing is the single most likely
 * way to build this wrong.
 */
public final class OutboundCallback {

    private static final String ROW_ID_PREFIX = "sim_cb_";
    private static final String EVENT_ID_PREFIX = "sim_evt_";

    private final String outboundCallbackId;
    private final String externalEventId;
    private final SimulatedPaymentId providerPaymentId;
    private final String callbackReference;
    private final SimulatedOutcome outcome;
    private final Instant occurredAt;
    private final Instant deliverAfter;
    private final String body;
    private final OutboundCallbackStatus status;
    private final int attempts;
    private final Instant lastAttemptAt;
    private final Integer lastResponseStatus;
    private final String lastResponseOutcome;
    private final Instant createdAt;
    private final Instant updatedAt;

    private OutboundCallback(
        String outboundCallbackId,
        String externalEventId,
        SimulatedPaymentId providerPaymentId,
        String callbackReference,
        SimulatedOutcome outcome,
        Instant occurredAt,
        Instant deliverAfter,
        String body,
        OutboundCallbackStatus status,
        int attempts,
        Instant lastAttemptAt,
        Integer lastResponseStatus,
        String lastResponseOutcome,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.outboundCallbackId = outboundCallbackId;
        this.externalEventId = externalEventId;
        this.providerPaymentId = providerPaymentId;
        this.callbackReference = callbackReference;
        this.outcome = outcome;
        this.occurredAt = occurredAt;
        this.deliverAfter = deliverAfter;
        this.body = body;
        this.status = status;
        this.attempts = attempts;
        this.lastAttemptAt = lastAttemptAt;
        this.lastResponseStatus = lastResponseStatus;
        this.lastResponseOutcome = lastResponseOutcome;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Queues a callback for delivery.
     *
     * @param externalEventId the PROVIDER's event id, and what PayMesh deduplicates on. Passed in
     *                        rather than minted here precisely so a caller can pass the SAME value
     *                        twice, which is the duplicate-callback scenario. {@link #newEventId()}
     *                        mints a fresh one for every other case
     */
    public static OutboundCallback enqueue(
        String externalEventId,
        SimulatedPaymentId providerPaymentId,
        String callbackReference,
        SimulatedOutcome outcome,
        Instant occurredAt,
        Instant deliverAfter,
        String body,
        Instant createdAt
    ) {
        if (externalEventId == null || externalEventId.isBlank()) {
            throw new IllegalArgumentException("An external event identifier is required");
        }

        if (providerPaymentId == null || outcome == null) {
            throw new IllegalArgumentException("A callback must name a payment and an outcome");
        }

        if (occurredAt == null || deliverAfter == null || createdAt == null) {
            throw new IllegalArgumentException("A callback must be timestamped");
        }

        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("A callback must carry a body");
        }

        return new OutboundCallback(
            ROW_ID_PREFIX + UUID.randomUUID(),
            externalEventId,
            providerPaymentId,
            callbackReference,
            outcome,
            occurredAt,
            deliverAfter,
            body,
            OutboundCallbackStatus.PENDING,
            0,
            null,
            null,
            null,
            createdAt,
            createdAt
        );
    }

    /** A fresh provider event id. {@code sim_evt_<uuid>}, the string PayMesh's tests already use. */
    public static String newEventId() {
        return EVENT_ID_PREFIX + UUID.randomUUID();
    }

    /**
     * PayMesh answered 2xx.
     *
     * @param responseOutcome APPLIED, DUPLICATE, IGNORED_STALE or IGNORED_TERMINAL, read out of the
     *                        response body. The status code is the retry signal and the body is the
     *                        detail (ADR-012 section 6), so a simulator that recorded only the code
     *                        could not tell whether its duplicate actually deduplicated
     */
    public OutboundCallback delivered(int responseStatus, String responseOutcome, Instant now) {
        return copyWith(
            OutboundCallbackStatus.DELIVERED, attempts + 1, now, responseStatus, responseOutcome,
            deliverAfter
        );
    }

    /**
     * The delivery did not land. Retried until the budget runs out, then abandoned.
     * <p>
     * A 404 takes this path and <b>should</b>: ADR-012 section 7 makes it a deliberate request to
     * retry, because the likeliest cause is a callback overtaking the transaction that created the
     * intent.
     */
    public OutboundCallback failed(
        Integer responseStatus,
        int maxAttempts,
        Instant retryAfter,
        Instant now
    ) {
        int used = attempts + 1;

        return copyWith(
            used >= maxAttempts ? OutboundCallbackStatus.ABANDONED : OutboundCallbackStatus.PENDING,
            used,
            now,
            responseStatus,
            null,
            retryAfter
        );
    }

    /** The mapper's entry point. Every field, because a row read back must be what was written. */
    public static OutboundCallback rehydrate(
        String outboundCallbackId,
        String externalEventId,
        SimulatedPaymentId providerPaymentId,
        String callbackReference,
        SimulatedOutcome outcome,
        Instant occurredAt,
        Instant deliverAfter,
        String body,
        OutboundCallbackStatus status,
        int attempts,
        Instant lastAttemptAt,
        Integer lastResponseStatus,
        String lastResponseOutcome,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new OutboundCallback(
            outboundCallbackId, externalEventId, providerPaymentId, callbackReference, outcome,
            occurredAt, deliverAfter, body, status, attempts, lastAttemptAt, lastResponseStatus,
            lastResponseOutcome, createdAt, updatedAt
        );
    }

    private OutboundCallback copyWith(
        OutboundCallbackStatus newStatus,
        int newAttempts,
        Instant now,
        Integer responseStatus,
        String responseOutcome,
        Instant newDeliverAfter
    ) {
        return new OutboundCallback(
            outboundCallbackId, externalEventId, providerPaymentId, callbackReference, outcome,
            occurredAt, newDeliverAfter, body, newStatus, newAttempts, now, responseStatus,
            responseOutcome, createdAt, now
        );
    }

    public String outboundCallbackId() {
        return outboundCallbackId;
    }

    public String externalEventId() {
        return externalEventId;
    }

    public SimulatedPaymentId providerPaymentId() {
        return providerPaymentId;
    }

    public String callbackReference() {
        return callbackReference;
    }

    public SimulatedOutcome outcome() {
        return outcome;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant deliverAfter() {
        return deliverAfter;
    }

    public String body() {
        return body;
    }

    public OutboundCallbackStatus status() {
        return status;
    }

    public int attempts() {
        return attempts;
    }

    public Instant lastAttemptAt() {
        return lastAttemptAt;
    }

    public Integer lastResponseStatus() {
        return lastResponseStatus;
    }

    public String lastResponseOutcome() {
        return lastResponseOutcome;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
