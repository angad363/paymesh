package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.net.URI;
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
 * <b>In this PR an attempt is created and never moved.</b> Confirm writes it at PROCESSING and
 * stops -- there is no outbound call anywhere in Payment yet, so nothing can answer. The states
 * beyond PROCESSING belong to the provider-callback PR.
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

    private final PaymentAttemptId paymentAttemptId;
    private final MerchantId merchantId;
    private final PaymentIntentId paymentIntentId;
    private final int attemptNumber;
    private final String provider;
    private final PaymentAttemptStatus status;
    private final long amountMinor;
    private final String currency;
    private final Map<String, String> requestPayload;
    private final Integer version;
    private final Instant createdAt;
    private final Instant updatedAt;

    private PaymentAttempt(
        PaymentAttemptId paymentAttemptId,
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        int attemptNumber,
        String provider,
        PaymentAttemptStatus status,
        long amountMinor,
        String currency,
        Map<String, String> requestPayload,
        Integer version,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.paymentAttemptId = paymentAttemptId;
        this.merchantId = merchantId;
        this.paymentIntentId = paymentIntentId;
        this.attemptNumber = attemptNumber;
        this.provider = provider;
        this.status = status;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.requestPayload = requestPayload;
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
            PaymentAttemptStatus.PROCESSING,
            intent.amountMinor(),
            intent.currency(),
            requestPayload(returnUrl, device),
            // Never persisted yet, so there is no row version to preserve. The adapter reads this
            // as "insert", which is what makes a second save of the same attempt fail on the
            // primary key rather than silently overwrite.
            null,
            startedAt,
            startedAt
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
     * and path are kept.
     * <p>
     * A merchant's return URL routinely carries a session token, a cart id or a signed callback
     * parameter in its query, and this row is a durable audit record that support staff read. SDD
     * 12.6 forbids storing raw provider payloads for the same reason. Keeping the origin and path
     * preserves everything an investigation needs -- where the customer was sent back to -- and
     * discards the part that is a credential.
     * <p>
     * A URL that cannot be parsed is dropped entirely rather than stored verbatim: an unparseable
     * string cannot be redacted, and storing what cannot be inspected is the failure mode this
     * method exists to prevent. Boundary validation on the request record is what tells the caller
     * their URL was malformed; by this point it has already passed.
     */
    private static String redact(String returnUrl) {
        String normalized = normalize(returnUrl, RETURN_URL_MAX_LENGTH, "Return URL");

        if (normalized == null) {
            return null;
        }

        try {
            URI parsed = URI.create(normalized);

            if (parsed.getScheme() == null || parsed.getHost() == null) {
                return null;
            }

            return parsed.getScheme() + "://" + parsed.getAuthority()
                + (parsed.getRawPath() == null ? "" : parsed.getRawPath());
        } catch (IllegalArgumentException exception) {
            return null;
        }
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

    public PaymentAttemptStatus status() {
        return status;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public String currency() {
        return currency;
    }

    public Map<String, String> requestPayload() {
        return requestPayload;
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
