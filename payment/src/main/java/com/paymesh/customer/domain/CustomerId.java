package com.paymesh.customer.domain;

import java.util.UUID;

public record CustomerId(String value) {
    private static final String PREFIX = "cus_";

    public CustomerId {
        validate(value);
    }

    public static CustomerId generate() {
        return new CustomerId(PREFIX + UUID.randomUUID());
    }

    public static CustomerId from(String value) {
        return new CustomerId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Customer Identifier cannot be null");
        }

        if(value.isBlank()) {
            throw new IllegalArgumentException("Customer Identifier cannot be blank");
        }

        if(!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Customer Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if(!uuid.toString().equals(uuidPart)) {
                throw new IllegalArgumentException("Customer Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Customer Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString () {
        return value;
    }

}
