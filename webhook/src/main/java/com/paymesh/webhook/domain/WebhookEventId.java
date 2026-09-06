package com.paymesh.webhook.domain;

import java.util.UUID;

/**
 * A published webhook event's opaque public identifier, {@code whv_} + UUID.
 *
 * <h2>WHY NOT {@code evt_}, WHICH IS THE OBVIOUS PREFIX FOR AN EVENT</h2>
 *
 * {@code evt_} already belongs to the outbox -- {@code shared.outbox.domain.EventId} defines it,
 * and it is the id stamped into {@code processed_events}. ADR-003 exists so that a prefix names the
 * type and a mis-routed id is <b>rejected rather than silently matching a row of another kind</b>;
 * two unrelated types sharing one prefix destroys exactly that property, and the failure mode is a
 * support query that quietly returns the wrong row.
 * <p>
 * The outbox's {@code evt_} value is not discarded -- it lives on
 * {@code webhook_events.source_event_id}, where it is the natural key that makes the fan-out
 * handler idempotent. Added to ADR-003 by ADR-028.
 */
public record WebhookEventId(String value) {

    private static final String PREFIX = "whv_";

    public WebhookEventId {
        validate(value);
    }

    public static WebhookEventId generate() {
        return new WebhookEventId(PREFIX + UUID.randomUUID());
    }

    public static WebhookEventId from(String value) {
        return new WebhookEventId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Webhook Event Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Webhook Event Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Webhook Event Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Webhook Event Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Webhook Event Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
