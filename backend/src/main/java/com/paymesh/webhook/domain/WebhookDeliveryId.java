package com.paymesh.webhook.domain;

import java.util.UUID;

/**
 * One attempt-to-tell-one-merchant-one-thing, {@code whd_} + UUID. Added to ADR-003 by ADR-028.
 * <p>
 * Public because it appears in a path: {@code POST /api/v1/webhook-deliveries/{id}/replay}.
 */
public record WebhookDeliveryId(String value) {

    private static final String PREFIX = "whd_";

    public WebhookDeliveryId {
        validate(value);
    }

    public static WebhookDeliveryId generate() {
        return new WebhookDeliveryId(PREFIX + UUID.randomUUID());
    }

    public static WebhookDeliveryId from(String value) {
        return new WebhookDeliveryId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Webhook Delivery Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Webhook Delivery Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException(
                "Webhook Delivery Identifier must start with " + PREFIX
            );
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException(
                    "Webhook Delivery Identifier contains an invalid UUID"
                );
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                "Webhook Delivery Identifier contains an invalid UUID", exception
            );
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
