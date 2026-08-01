package com.paymesh.shared.outbox.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One domain event, as it will be handed to a relay: the SDD 22.1 envelope plus its payload.
 * <p>
 * There is no published_at here. Publication is a fact about delivery, not about the event, and
 * nothing in this codebase publishes yet -- the column exists in the table and no Java code reads
 * or writes it.
 *
 * @param aggregateType the kind of thing the event is about ("ORDER", "PAYMENT_INTENT"). A String
 *                      rather than an enum because the outbox is shared platform code: an enum here
 *                      would have to be edited by every capability that ever emits an event, which
 *                      is the coupling the shared package exists to avoid.
 * @param eventVersion  the envelope version, always positive. Mirrors ck_outbox_events_version.
 */
public record OutboxEvent(
    EventId eventId,
    MerchantId merchantId,
    String aggregateType,
    String aggregateId,
    String eventType,
    int eventVersion,
    Map<String, Object> payload,
    Instant occurredAt
) {

    public OutboxEvent {
        if (eventId == null) {
            throw new IllegalArgumentException("Event Identifier cannot be null");
        }

        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        requireText(aggregateType, "Aggregate type");
        requireText(aggregateId, "Aggregate identifier");
        requireText(eventType, "Event type");

        if (eventVersion <= 0) {
            throw new IllegalArgumentException("Event version must be positive");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("Event timestamp cannot be null");
        }

        // Defensive copy: the caller builds this map and may keep a reference to it. An event that
        // could change after it was appended would make the outbox unauditable.
        //
        // Not Map.copyOf, which refuses a null value. An optional field that is genuinely absent (a
        // guest checkout has no customer) is carried as an explicit JSON null so that every event of
        // a type has the same shape, rather than one whose keys come and go.
        payload = payload == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    private static void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
    }
}
