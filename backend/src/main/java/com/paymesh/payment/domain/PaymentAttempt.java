package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One try at collecting an intent: one conversation with one provider, with its own outcome.
 * <p>
 * An intent can outlive a failed attempt and be confirmed again, which is why this is a separate
 * aggregate and not a handful of columns on {@code payment_intents}. Squashing the two would
 * destroy the record of every try but the last, and "what did we actually ask the provider, and
 * when" is the question an unexplained charge is investigated with.
 * <p>
 * Confirm writes it at PROCESSING and stops -- there is no outbound call anywhere in Payment, so
 * nothing here can answer. A provider callback is the only thing that moves it, through
 * {@link #recordProviderEvent}.
 * <p>
 * Instances are immutable, like every other aggregate here.
 */
public final class PaymentAttempt {

    /**
     * The only provider there is, and it does not exist yet.
     * <p>
     * A constant rather than an enum or a parameter: there is nothing to choose between, and a
     * one-valued enum is a decision dressed up as flexibility. The value names the seam the Provider
     * Simulator will plug into, so the row a real provider eventually writes has the same shape as
     * the rows written today.
     */
    public static final String SIMULATOR = "SIMULATOR";

    private static final int RETURN_URL_MAX_LENGTH = 500;
    private static final int DEVICE_MAX_LENGTH = 120;
    private static final int PROVIDER_REFERENCE_MAX_LENGTH = 120;
    private static final int FAILURE_CODE_MAX_LENGTH = 60;
    private static final int FAILURE_MESSAGE_MAX_LENGTH = 500;

    private final PaymentAttemptId paymentAttemptId;
    private final MerchantId merchantId;
    private final PaymentIntentId paymentIntentId;
    private final int attemptNumber;
    private final String provider;
    private final String providerReference;
    private final PaymentAttemptStatus status;
    private final long amountMinor;
    private final String currency;
    private final String failureCode;
    private final String failureMessage;
    private final Instant lastProviderEventAt;
    private final Map<String, String> requestPayload;
    private final Map<String, Object> responsePayload;
    private final Integer version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private PaymentAttempt(
        PaymentAttemptId paymentAttemptId,
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        int attemptNumber,
        String provider,
        String providerReference,
        PaymentAttemptStatus status,
        long amountMinor,
        String currency,
        String failureCode,
        String failureMessage,
        Instant lastProviderEventAt,
        Map<String, String> requestPayload,
        Map<String, Object> responsePayload,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentAttemptId = paymentAttemptId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.attemptNumber = attemptNumber;
        this.provider = provider;
        this.providerReference = providerReference;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.failureCode = failureCode;
        this.failureMessage = failureMessage;
        this.lastProviderEventAt = lastProviderEventAt;
        this.requestPayload = requestPayload;
        this.responsePayload = responsePayload;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /**
     * Opens an attempt against an intent.
     * <p>
     * The intent is passed whole rather than as an amount and a currency so that an attempt cannot
     * be opened for a figure the intent never carried: the money on the attempt is COPIED from the
     * aggregate, in one place, and a caller has nothing to get wrong.
     *
     * @param returnUrl where the customer comes back to after an off-site step, or null. Stored
     *                  REDACTED -- see {@link #redact}.
     * @param device    an opaque client hint ("web", "ios"), or null. Nothing reads it yet.
     */
    public static PaymentAttempt start(
        PaymentAttemptId paymentAttemptId,
        PaymentIntent intent,
        int attemptNumber,
        String returnUrl,
        String device,
        Instant startedAt
    ) {
        if (paymentAttemptId == null) {
            throw new IllegalArgumentException("Payment Attempt Identifier cannot be null");
        }

        if (intent == null) {
            throw new IllegalArgumentException("An attempt must belong to a payment intent");
        }

        if (attemptNumber < 1) {
            throw new IllegalArgumentException("Attempt number must be 1 or greater");
        }

        if (startedAt == null) {
            throw new IllegalArgumentException("Attempt timestamp cannot be null");
        }

        return new PaymentAttempt(
            paymentAttemptId,
            intent.merchantId(),
            intent.paymentIntentId(),
            attemptNumber,
            SIMULATOR,
            // Nothing was called, so nothing has answered: no reference, no outcome detail, and no
            // provider event to have a clock for.
            null,
            PaymentAttemptStatus.PROCESSING,
            intent.amountMinor(),
            intent.currency(),
            null,
            null,
            null,
            requestPayload(returnUrl, device),
            null,
            // Never persisted yet, so there is no row version to preserve. The adapter reads this
            // as "insert", which is what makes a second save of the same attempt fail on the
            // primary key rather than silently overwrite.
            null,
            startedAt,
            startedAt
        );
    }

    /**
     * Rebuilds an attempt from already-persisted state. Deliberately does NOT re-normalize: those
     * values passed through {@link #start} or {@link #recordProviderEvent} before they were stored,
     * so recomputing on read would mask corruption rather than repair it.
     */
    public static PaymentAttempt reconstitute(
        PaymentAttemptId paymentAttemptId,
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        int attemptNumber,
        String provider,
        String providerReference,
        PaymentAttemptStatus status,
        long amountMinor,
        String currency,
        String failureCode,
        String failureMessage,
        Instant lastProviderEventAt,
        Map<String, String> requestPayload,
        Map<String, Object> responsePayload,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        return new PaymentAttempt(
            paymentAttemptId,
            merchantId,
            paymentIntentId,
            attemptNumber,
            provider,
            providerReference,
            status,
            amountMinor,
            currency,
            failureCode,
            failureMessage,
            lastProviderEventAt,
            requestPayload == null ? Map.of() : Map.copyOf(requestPayload),
            responsePayload,
            version,
            createdAt,
            updatedAt
        );
    }

    /**
     * What the provider said about this try, written down.
     * <p>
     * <b>{@code occurredAt} becomes {@code last_provider_event_at}, which is half of the
     * out-of-order guard</b> (ADR-012). The comparison that decides whether an event is stale is not
     * here: it is made across ALL of an intent's attempts before this is called, because the state
     * machine's cycle -- PROCESSING to REQUIRES_ACTION and back -- spans two attempts, and a clock
     * that reset with each new attempt would wave through exactly the stale event it exists to
     * refuse. This method records; it does not judge.
     * <p>
     * The response payload arrives REDACTED. Nothing in this class re-redacts it, because a value
     * that reached here unredacted is a bug in the caller and quietly cleaning it up would hide
     * that.
     */
    public PaymentAttempt recordProviderEvent(
        PaymentAttemptStatus newStatus,
        String providerReference,
        String failureCode,
        String failureMessage,
        Map<String, Object> redactedResponsePayload,
        Instant occurredAt,
        Instant recordedAt
    ) {
        if (newStatus == null) {
            throw new IllegalArgumentException("Attempt status cannot be null");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Provider event timestamp cannot be null");
        }

        if (recordedAt == null) {
            throw new IllegalArgumentException("Attempt timestamp cannot be null");
        }

        String reference = normalize(
            providerReference, PROVIDER_REFERENCE_MAX_LENGTH, "Provider reference"
        );

        return new PaymentAttempt(
            paymentAttemptId,
            merchantId,
            paymentIntentId,
            attemptNumber,
            provider,
            // A reference already recorded is kept when the provider stops repeating it: a later
            // event about the same try does not un-name it.
            reference == null ? this.providerReference : reference,
            newStatus,
            amountMinor,
            currency,
            normalize(failureCode, FAILURE_CODE_MAX_LENGTH, "Failure code"),
            normalize(failureMessage, FAILURE_MESSAGE_MAX_LENGTH, "Failure message"),
            occurredAt,
            requestPayload,
            redactedResponsePayload == null || redactedResponsePayload.isEmpty()
                ? null
                : Map.copyOf(redactedResponsePayload),
            version,
            createdAt,
            recordedAt
        );
    }

    /**
     * What was asked for, as it will be stored. An empty map rather than null when the caller sent
     * neither field, so the column holds SQL NULL and not an empty object -- the mapper decides
     * that, the same way it does for an intent's metadata.
     */
    private static Map<String, String> requestPayload(String returnUrl, String device) {
        Map<String, String> payload = new LinkedHashMap<>();

        String url = redact(returnUrl);
        String client = normalize(device, DEVICE_MAX_LENGTH, "Device");

        if (url != null) {
            payload.put("returnUrl", url);
        }

        if (client != null) {
            payload.put("device", client);
        }

        return Map.copyOf(payload);
    }

    /**
     * REDACTION, SUCH AS IT IS: the query string and the fragment are dropped and only the origin
     * and path are kept. The rule itself lives in {@link Redaction}, because a provider's action URL
     * needs exactly the same treatment for exactly the same reason.
     * <p>
     * Boundary validation on the request record is what tells the caller their URL was malformed; by
     * this point it has already passed, so an unparseable value is dropped rather than reported.
     */
    private static String redact(String returnUrl) {
        return Redaction.url(normalize(returnUrl, RETURN_URL_MAX_LENGTH, "Return URL"));
    }

    private static String normalize(String value, int maxLength, String fieldName) {
        if (value == null) {
            return null;
        }

        String normalized = value.trim();

        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                fieldName + " cannot be longer than " + maxLength + " characters"
            );
        }

        return normalized;
    }

    public PaymentAttemptId paymentAttemptId() {
        return paymentAttemptId;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public PaymentIntentId paymentIntentId() {
        return paymentIntentId;
    }

    public int attemptNumber() {
        return attemptNumber;
    }

    public String provider() {
        return provider;
    }

    /** The provider's own identifier for this try. Null until a callback names one. */
    public String providerReference() {
        return providerReference;
    }

    public PaymentAttemptStatus status() {
        return status;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public String failureCode() {
        return failureCode;
    }

    public String failureMessage() {
        return failureMessage;
    }

    /**
     * The provider timestamp of the last event applied to this attempt, or null if none has been.
     * Half of the out-of-order guard -- see {@link #recordProviderEvent} for why the comparison is
     * made across an intent's attempts rather than on this one alone.
     */
    public Instant lastProviderEventAt() {
        return lastProviderEventAt;
    }

    public Map<String, String> requestPayload() {
        return requestPayload;
    }

    /** The provider's answer, redacted. Null until one arrives. */
    public Map<String, Object> responsePayload() {
        return responsePayload;
    }

    public Integer version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
