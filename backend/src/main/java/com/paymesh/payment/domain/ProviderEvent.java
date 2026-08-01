package com.paymesh.payment.domain;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One provider callback body, parsed and validated -- what the provider claims happened.
 * <p>
 * A domain record rather than the API request record carried inward, because the fields listed here
 * <b>are the redaction allowlist</b>: {@link #redactedPayload()} can only emit what this record can
 * hold, so a provider that starts sending a card number, a customer's address or a raw authorization
 * blob has nowhere for it to land. Redacting by allowlist rather than denylist is SDD 12.6's rule
 * and design section 4.5's -- a denylist grows a hole the first time a provider adds a field, and
 * the hole is silent.
 *
 * @param eventId           the provider's own id for this delivery. The deduplication key, with the
 *                          provider name.
 * @param occurredAt        the provider's timestamp. The ordering key, and the value the monotonic
 *                          guard compares -- see {@code RecordProviderCallbackService}.
 * @param paymentIntentId   which intent, when the provider names one. Null is permitted only
 *                          because {@code providerReference} is the other way to resolve it.
 * @param providerReference the provider's id for the attempt. Both the fallback resolution path and
 *                          the value written onto the attempt.
 * @param actionUrl         where to send the customer for an off-site step. Stored REDACTED -- a 3DS
 *                          challenge URL carries a token in its query string.
 */
public record ProviderEvent(
    String eventId,
    Instant occurredAt,
    String paymentIntentId,
    String providerReference,
    ProviderOutcome outcome,
    Long authorizedAmountMinor,
    Long capturedAmountMinor,
    String failureCode,
    String failureMessage,
    String actionUrl
) {

    public static final int MAX_EVENT_ID_LENGTH = 120;

    public ProviderEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("Provider event identifier cannot be blank");
        }

        if (eventId.length() > MAX_EVENT_ID_LENGTH) {
            throw new IllegalArgumentException(
                "Provider event identifier cannot be longer than " + MAX_EVENT_ID_LENGTH
                    + " characters"
            );
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Provider event timestamp cannot be null");
        }

        if (outcome == null) {
            throw new IllegalArgumentException("Provider outcome cannot be null");
        }

        // One of the two, or there is nothing to resolve. A body naming neither is malformed rather
        // than unknown, which is why it is a 400 and not the 404 an unresolvable name earns.
        if (isBlank(paymentIntentId) && isBlank(providerReference)) {
            throw new IllegalArgumentException(
                "A provider callback must name either a paymentIntentId or a providerReference"
            );
        }
    }

    /**
     * THE ALLOWLIST, as it will be stored in {@code provider_callbacks.payload} and on the attempt's
     * {@code response_payload}.
     * <p>
     * Absent fields are omitted rather than written as JSON nulls: this is an audit record read by a
     * human or a reconciliation job, not an event envelope a consumer parses positionally, and a row
     * that carries only what the provider actually said is the more honest one.
     */
    public Map<String, Object> redactedPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();

        payload.put("eventId", eventId);
        payload.put("outcome", outcome.name());
        payload.put("occurredAt", occurredAt.toString());

        putIfPresent(payload, "paymentIntentId", paymentIntentId);
        putIfPresent(payload, "providerReference", providerReference);
        putIfPresent(payload, "authorizedAmountMinor", authorizedAmountMinor);
        putIfPresent(payload, "capturedAmountMinor", capturedAmountMinor);
        putIfPresent(payload, "failureCode", failureCode);
        putIfPresent(payload, "failureMessage", failureMessage);
        // The query string and fragment are dropped: a challenge URL's query is a credential.
        putIfPresent(payload, "actionUrl", Redaction.url(actionUrl));

        return Collections.unmodifiableMap(payload);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
        if (value != null && !(value instanceof String text && text.isBlank())) {
            payload.put(key, value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
