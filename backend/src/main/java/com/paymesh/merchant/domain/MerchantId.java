package com.paymesh.merchant.domain;

import java.util.UUID;

public record MerchantId(String value) {
    private static final String PREFIX = "mrc_";

    public MerchantId {
        validate(value);
    }

    public static MerchantId generate() {
        return new MerchantId(PREFIX + UUID.randomUUID());
    }

    public static MerchantId from(String value) {
        return new MerchantId(value);
    }

    private static void validate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Merchant Identifier cannot be null");
        }

        if(value.isBlank()) {
            throw new IllegalArgumentException("Merchant Identifier cannot be blank");
        }

        if(!value.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Merchant Identifier must start with " + PREFIX);
        }

        String uuidPart = value.substring(PREFIX.length());

        try {
            UUID uuid = UUID.fromString(uuidPart);

            if(!uuid.toString().equalsIgnoreCase(uuidPart)) {
                throw new IllegalArgumentException("Merchant Identifier contains an invalid UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Merchant Identifier contains an invalid UUID", exception);
        }
    }

    @Override
    public String toString () {
        return value;
    }

}
