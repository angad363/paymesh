package com.paymesh.shared.outbox.domain;

import java.util.UUID;

/**
 * The identifier of a single domain event, "evt_" + UUID (ADR-003).
 * <p>
 * Unlike an idempotency record, this one IS public: SDD 22.1 puts it in the event envelope, so it
 * crosses the wire and is the value a consumer dedups on. That is why it is a validated value
 * object rather than a bare String.
 */
public record EventId(String value) {

    private static final String PREFIX = "evt_";

    public EventId {
        validate(value);
    }

    public static EventId generate() {
        return new EventId(PREFIX + UUID.randomUUID());
    }

    public static EventId from(String value) {
        return new EventId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Event Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Event Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Event Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException("Event Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Event Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
