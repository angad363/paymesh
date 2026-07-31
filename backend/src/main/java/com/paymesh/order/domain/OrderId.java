package com.paymesh.order.domain;

import java.util.UUID;

public record OrderId(String value) {
    private static final String PREFIX = "ord_";

    public OrderId {
        validate(value);
    }

    public static OrderId generate() {
        return new OrderId(PREFIX + UUID.randomUUID());
    }

    public static OrderId from(String value) {
        return new OrderId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Order Identifier cannot be null");
        }

        if (value.isBlank()) {
            throw new IllegalArgumentException("Order Identifier cannot be blank");
        }

        if (!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Order Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if (!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException("Order Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Order Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
