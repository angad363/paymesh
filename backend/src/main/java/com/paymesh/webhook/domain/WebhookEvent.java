package com.paymesh.webhook.domain;

import java.time.Instant;

/**
 * The external event, serialized once and never again.
 *
 * <h2>NOT A COPY OF AN OUTBOX EVENT, AND THAT IS THE DESIGN (SDD §18.2)</h2>
 *
 * {@code payment.succeeded} on the outbox is PayMesh's event, with PayMesh's field names and
 * PayMesh's internal shape. On the wire it is the merchant's: versioned, documented, and stable
 * across any refactor that renames something internal. A translator sits between them.
 *
 * <h2>THE PAYLOAD IS A STRING, NOT A MAP, AND THAT IS THE INVARIANT</h2>
 *
 * A merchant verifies an HMAC computed over the bytes they received, so a replay must resend
 * <b>the same bytes</b> -- not equivalent JSON. One character of drift and their check fails. Hold
 * the payload as a parsed structure and the bytes become whatever the serializer emits next time,
 * which depends on map ordering, Jackson configuration and the mood of a future refactor.
 * <p>
 * So it is serialized once, at translation, and carried as an opaque string from there to the
 * socket. {@code webhook_events.payload} is {@code TEXT} for the same reason, behind an
 * immutability trigger, and the request goes out as raw UTF-8 rather than through a converter that
 * might re-serialize it. ADR-028 §3.1.
 */
public final class WebhookEvent {

    /** The merchant-facing contract version. One translator, one version, for now. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    private final WebhookEventId webhookEventId;
    private final String merchantId;
    private final String sourceEventId;
    private final String eventType;
    private final int schemaVersion;
    private final String payload;
    private final Instant occurredAt;
    private final Instant createdAt;

    private WebhookEvent(
        WebhookEventId webhookEventId,
        String merchantId,
        String sourceEventId,
        String eventType,
        int schemaVersion,
        String payload,
        Instant occurredAt,
        Instant createdAt
    ) {
        this.webhookEventId = webhookEventId;
        this.merchantId = merchantId;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.createdAt = createdAt;
    }

    /**
     * @param sourceEventId the outbox {@code evt_} id. Unique, and the natural key that makes the
     *     fan-out handler idempotent -- the inbox stops the SAME event twice, not a different event
     *     describing the same fact, which is what {@code EventHandler}'s own contract warns about.
     * @param payload already serialized, because serializing later would defeat the point
     */
    public static WebhookEvent translate(
        String merchantId,
        String sourceEventId,
        String eventType,
        String payload,
        Instant occurredAt,
        Instant now
    ) {
        if (merchantId == null || merchantId.isBlank()) {
            throw new IllegalArgumentException("A webhook event belongs to a merchant");
        }

        if (sourceEventId == null || sourceEventId.isBlank()) {
            throw new IllegalArgumentException("A webhook event names the outbox event it came from");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("A webhook event has a type");
        }

        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("A webhook event carries a serialized payload");
        }

        if (occurredAt == null || now == null) {
            throw new IllegalArgumentException("A timestamp is required");
        }

        return new WebhookEvent(
            WebhookEventId.generate(), merchantId, sourceEventId, eventType,
            CURRENT_SCHEMA_VERSION, payload, occurredAt, now
        );
    }

    public static WebhookEvent rehydrate(
        WebhookEventId webhookEventId,
        String merchantId,
        String sourceEventId,
        String eventType,
        int schemaVersion,
        String payload,
        Instant occurredAt,
        Instant createdAt
    ) {
        return new WebhookEvent(
            webhookEventId, merchantId, sourceEventId, eventType,
            schemaVersion, payload, occurredAt, createdAt
        );
    }

    public WebhookEventId webhookEventId() {
        return webhookEventId;
    }

    public String merchantId() {
        return merchantId;
    }

    public String sourceEventId() {
        return sourceEventId;
    }

    public String eventType() {
        return eventType;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String payload() {
        return payload;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
