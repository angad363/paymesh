package com.paymesh.payment.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Map;

/**
 * One delivery from a provider, and what this platform did about it.
 * <p>
 * The row exists for every judged delivery, including the ones that changed nothing: a refused event
 * that left no trace would be re-judged from scratch on every re-delivery, and an
 * {@code IGNORED_TERMINAL} row is the only record of a genuine PayMesh/provider divergence.
 * <p>
 * <b>{@code (provider, externalEventId)} is the deduplication key and it is not merchant-leading</b>
 * -- see V10's comment, which is the one a reviewer will actually read. The merchant here is DERIVED
 * from the intent the callback named; the caller never supplied it.
 *
 * @param payloadHash SHA-256 of the RAW body, hex, computed before the body was parsed. Two
 *                    deliveries sharing an event id but disagreeing on content are findable by
 *                    comparing this with the stored payload; the first delivery is the one that took
 *                    effect.
 * @param payload     the body after allowlist redaction -- {@link ProviderEvent#redactedPayload()}.
 *                    Raw provider payloads are never stored (SDD 12.6).
 * @param receivedAt  when this platform took delivery, which is not {@code occurredAt}. The gap
 *                    between them is how late the delivery was.
 */
public record ProviderCallback(
    String provider,
    String externalEventId,
    MerchantId merchantId,
    PaymentIntentId paymentIntentId,
    String payloadHash,
    Map<String, Object> payload,
    ProviderCallbackOutcome outcome,
    Instant occurredAt,
    Instant receivedAt,
    Instant processedAt
) {

    private static final int PAYLOAD_HASH_LENGTH = 64;

    public ProviderCallback {
        requireText(provider, "Provider");
        requireText(externalEventId, "Provider event identifier");

        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        if (paymentIntentId == null) {
            throw new IllegalArgumentException("Payment Intent Identifier cannot be null");
        }

        if (payloadHash == null || payloadHash.length() != PAYLOAD_HASH_LENGTH) {
            throw new IllegalArgumentException(
                "Payload hash must be " + PAYLOAD_HASH_LENGTH + " hex characters"
            );
        }

        if (payload == null || payload.isEmpty()) {
            throw new IllegalArgumentException("Payload cannot be empty");
        }

        // DUPLICATE is not storable and ck_provider_callbacks_outcome agrees: by the time it is
        // known, this transaction is rolling back on the primary key.
        if (outcome == null || outcome == ProviderCallbackOutcome.DUPLICATE) {
            throw new IllegalArgumentException(
                "A stored callback outcome must be APPLIED, IGNORED_STALE or IGNORED_TERMINAL"
            );
        }

        if (occurredAt == null || receivedAt == null || processedAt == null) {
            throw new IllegalArgumentException("Callback timestamps cannot be null");
        }

        payload = Map.copyOf(payload);
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
    }
}
