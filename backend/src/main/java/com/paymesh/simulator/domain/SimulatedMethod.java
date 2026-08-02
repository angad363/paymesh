package com.paymesh.simulator.domain;

import java.util.Locale;

/** The rail the provider pretends to have used (SDD 4.1). Recorded; not yet behaviourally significant. */
public enum SimulatedMethod {

    CARD,
    UPI,
    WALLET,
    BANK;

    public static SimulatedMethod parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payment method cannot be blank");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown payment method: " + value);
        }
    }
}
