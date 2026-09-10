package com.paymesh.notification.domain;

import java.util.UUID;

/**
 * A notification's opaque public identifier, {@code nfn_} + UUID (ADR-003, ADR-033).
 *
 * <p>Its own id, distinct from the {@code evt_} source event that produced it: that value lives on
 * {@code notifications.source_event_id} where it is the natural key the handler deduplicates on, the
 * same split Webhook draws between {@code whv_} and {@code source_event_id}.
 */
public record NotificationId(String value) {

    private static final String PREFIX = "nfn_";

    public NotificationId {
        validate(value);
    }

    public static NotificationId generate() {
        return new NotificationId(PREFIX + UUID.randomUUID());
    }

    public static NotificationId from(String value) {
        return new NotificationId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Notification Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Notification Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Notification Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Notification Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Notification Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
