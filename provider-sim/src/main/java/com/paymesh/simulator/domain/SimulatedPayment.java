package com.paymesh.simulator.domain;

import java.time.Instant;
import java.util.Locale;

/**
 * The provider's own record of a payment it was asked to take.
 * <p>
 * <b>This is not a PayMesh payment and holds none of PayMesh's state</b> (SDD 13.2). It has no
 * merchant, no order and no intent -- only a {@link #callbackReference()}, which is the caller's own
 * string echoed back into every callback and never interpreted. A real provider models exactly that
 * as a merchant reference passthrough.
 * <p>
 * Instances are immutable. {@link #capture} and {@link #recordRefund} return new instances rather
 * than mutating this one, matching the aggregate style used across PayMesh, and there is
 * deliberately no status setter: callers request a transition and the state machine decides whether
 * it is legal.
 */
public final class SimulatedPayment {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 120;
    private static final int MAX_CALLBACK_REFERENCE_LENGTH = 60;
    private static final int MAX_TOKEN_LENGTH = 60;
    private static final int CURRENCY_LENGTH = 3;

    /** What a declined simulated issuer says. The vocabulary a real card network uses. */
    public static final String DECLINE_CODE = "do_not_honour";
    public static final String DECLINE_MESSAGE = "The issuer declined the transaction.";

    private final SimulatedPaymentId providerPaymentId;
    private final String idempotencyKey;
    private final String requestHash;
    private final String callbackReference;
    private final SimulatedMethod method;
    private final String token;
    private final SimulatedBehaviour behaviour;
    private final long amountMinor;
    private final String currency;
    private final SimulatedCaptureMethod captureMethod;
    private final SimulatedPaymentStatus status;
    private final long capturedAmountMinor;
    private final long refundedAmountMinor;
    private final String failureCode;
    private final String failureMessage;
    private final Instant createdAt;
    private final Instant updatedAt;

    private SimulatedPayment(
        SimulatedPaymentId providerPaymentId,
        String idempotencyKey,
        String requestHash,
        String callbackReference,
        SimulatedMethod method,
        String token,
        SimulatedBehaviour behaviour,
        long amountMinor,
        String currency,
        SimulatedCaptureMethod captureMethod,
        SimulatedPaymentStatus status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.providerPaymentId = providerPaymentId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.callbackReference = callbackReference;
        this.method = method;
        this.token = token;
        this.behaviour = behaviour;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.captureMethod = captureMethod;
        this.status = status;
        this.capturedAmountMinor = capturedAmountMinor;
        this.refundedAmountMinor = refundedAmountMinor;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Takes the payment, and decides in one place what the provider did about it.
     * <p>
     * The outcome is a pure function of the resolved behaviour and the capture method, computed here
     * rather than in the service, so that "what does {@code tok_sim_decline} mean" has exactly one
     * implementation and cannot be re-derived differently by the capture path or by a test fixture.
     */
    public static SimulatedPayment authorize(
        SimulatedPaymentId providerPaymentId,
        String idempotencyKey,
        String requestHash,
        String callbackReference,
        SimulatedMethod method,
        String token,
        SimulatedBehaviour behaviour,
        long amountMinor,
        String currency,
        SimulatedCaptureMethod captureMethod,
        Instant createdAt
    ) {
        requireNotNull(providerPaymentId, "Simulated payment identifier");
        requireNotNull(method, "Payment method");
        requireNotNull(behaviour, "Simulated behaviour");
        requireNotNull(captureMethod, "Capture method");
        requireNotNull(createdAt, "Creation timestamp");

        SimulatedPaymentStatus status = statusFor(behaviour, captureMethod);

        return new SimulatedPayment(
            providerPaymentId,
            requireText(idempotencyKey, MAX_IDEMPOTENCY_KEY_LENGTH, "Idempotency key"),
            requireHash(requestHash),
            requireText(callbackReference, MAX_CALLBACK_REFERENCE_LENGTH, "Callback reference"),
            method,
            requireText(token, MAX_TOKEN_LENGTH, "Payment token"),
            behaviour,
            requireAmount(amountMinor),
            requireCurrency(currency),
            captureMethod,
            status,
            status == SimulatedPaymentStatus.CAPTURED ? amountMinor : 0,
            0,
            status == SimulatedPaymentStatus.DECLINED ? DECLINE_CODE : null,
            status == SimulatedPaymentStatus.DECLINED ? DECLINE_MESSAGE : null,
            createdAt,
            createdAt
        );
    }

    /**
     * The behaviour table, and the only place it is written down.
     * <p>
     * {@code DUPLICATE_CALLBACK} and {@code STALE_CALLBACK} deliberately ignore the capture method
     * and behave as an automatic success. Both exist to exercise PayMesh's <em>delivery</em>
     * mechanisms rather than its capture mechanisms, and combining a re-delivery scenario with a
     * two-step capture would make a failing test ambiguous about which half broke.
     */
    private static SimulatedPaymentStatus statusFor(
        SimulatedBehaviour behaviour,
        SimulatedCaptureMethod captureMethod
    ) {
        return switch (behaviour) {
            case DECLINE -> SimulatedPaymentStatus.DECLINED;
            case REQUIRE_ACTION -> SimulatedPaymentStatus.REQUIRES_ACTION;
            case TIMEOUT -> SimulatedPaymentStatus.TIMED_OUT;
            case DUPLICATE_CALLBACK, STALE_CALLBACK -> SimulatedPaymentStatus.CAPTURED;
            case SUCCEED -> captureMethod == SimulatedCaptureMethod.MANUAL
                ? SimulatedPaymentStatus.AUTHORIZED
                : SimulatedPaymentStatus.CAPTURED;
        };
    }

    /**
     * Captures an authorization, in full or in part.
     *
     * @throws SimulatedPaymentNotCapturableException from any state but AUTHORIZED. A second capture
     *                                                of a CAPTURED payment is the one that would
     *                                                collect twice, so it is refused here rather
     *                                                than left to the CHECK constraint
     */
    public SimulatedPayment capture(long requestedAmountMinor, Instant now) {
        requireNotNull(now, "Capture timestamp");

        if (status != SimulatedPaymentStatus.AUTHORIZED) {
            throw new SimulatedPaymentNotCapturableException(providerPaymentId, status);
        }

        if (requestedAmountMinor <= 0) {
            throw new IllegalArgumentException("Capture amount must be a positive number of minor units");
        }

        if (requestedAmountMinor > amountMinor) {
            throw new CaptureExceedsAuthorizedAmountException(
                providerPaymentId, requestedAmountMinor, amountMinor
            );
        }

        return copyWith(
            SimulatedPaymentStatus.CAPTURED, requestedAmountMinor, refundedAmountMinor, now
        );
    }

    /**
     * Records money going back out.
     * <p>
     * The application checks this under a row lock so the caller gets a readable 422, and
     * {@code ck_provider_payments_refunded} in V13 is what actually guarantees it. This method is the
     * middle of those three and the only one that can name the amounts in its message.
     */
    public SimulatedPayment recordRefund(long refundAmountMinor, Instant now) {
        requireNotNull(now, "Refund timestamp");

        if (refundAmountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be a positive number of minor units");
        }

        if (refundAmountMinor > refundableAmountMinor()) {
            throw new RefundExceedsCapturedAmountException(
                providerPaymentId, refundAmountMinor, refundableAmountMinor()
            );
        }

        return copyWith(status, capturedAmountMinor, refundedAmountMinor + refundAmountMinor, now);
    }

    /** What is left to give back. */
    public long refundableAmountMinor() {
        return capturedAmountMinor - refundedAmountMinor;
    }

    /**
     * Rebuilds an instance from storage, bypassing the transitions.
     * <p>
     * The mapper's entry point and nothing else's. It takes every field because a row read back must
     * be exactly what was written -- re-deriving the status from the behaviour here would silently
     * undo a capture on every read.
     */
    public static SimulatedPayment rehydrate(
        SimulatedPaymentId providerPaymentId,
        String idempotencyKey,
        String requestHash,
        String callbackReference,
        SimulatedMethod method,
        String token,
        SimulatedBehaviour behaviour,
        long amountMinor,
        String currency,
        SimulatedCaptureMethod captureMethod,
        SimulatedPaymentStatus status,
        long capturedAmountMinor,
        long refundedAmountMinor,
        String failureCode,
        String failureMessage,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new SimulatedPayment(
            providerPaymentId, idempotencyKey, requestHash, callbackReference, method, token,
            behaviour, amountMinor, currency, captureMethod, status, capturedAmountMinor,
            refundedAmountMinor, failureCode, failureMessage, createdAt, updatedAt
        );
    }

    private SimulatedPayment copyWith(
        SimulatedPaymentStatus newStatus,
        long newCaptured,
        long newRefunded,
        Instant now
    ) {
        return new SimulatedPayment(
            providerPaymentId, idempotencyKey, requestHash, callbackReference, method, token,
            behaviour, amountMinor, currency, captureMethod, newStatus, newCaptured, newRefunded,
            failureCode, failureMessage, createdAt, now
        );
    }

    public SimulatedPaymentId providerPaymentId() {
        return providerPaymentId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String requestHash() {
        return requestHash;
    }

    public String callbackReference() {
        return callbackReference;
    }

    public SimulatedMethod method() {
        return method;
    }

    public String token() {
        return token;
    }

    public SimulatedBehaviour behaviour() {
        return behaviour;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public SimulatedCaptureMethod captureMethod() {
        return captureMethod;
    }

    public SimulatedPaymentStatus status() {
        return status;
    }

    public long capturedAmountMinor() {
        return capturedAmountMinor;
    }

    public long refundedAmountMinor() {
        return refundedAmountMinor;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    private static void requireNotNull(Object value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " is required");
        }
    }

    private static String requireText(String value, int maxLength, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " cannot be blank");
        }

        String trimmed = value.trim();

        if (trimmed.length() > maxLength) {
            throw new IllegalArgumentException(
                what + " cannot be longer than " + maxLength + " characters"
            );
        }

        return trimmed;
    }

    private static String requireHash(String value) {
        if (value == null || value.length() != 64) {
            throw new IllegalArgumentException("Request hash must be 64 hexadecimal characters");
        }

        return value;
    }

    private static long requireAmount(long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Amount must be a positive number of minor units");
        }

        return amountMinor;
    }

    private static String requireCurrency(String currency) {
        if (currency == null || currency.trim().length() != CURRENCY_LENGTH) {
            throw new IllegalArgumentException("Currency must be a 3-letter ISO 4217 code");
        }

        return currency.trim().toUpperCase(Locale.ROOT);
    }
}
