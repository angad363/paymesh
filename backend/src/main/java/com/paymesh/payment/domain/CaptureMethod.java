package com.paymesh.payment.domain;

import java.util.Locale;

/**
 * Whether an authorized payment is captured immediately or held for the merchant to capture later.
 * <p>
 * Chosen at creation and never changed, which is why it is written from this PR even though
 * nothing reaches AUTHORIZED until provider callbacks land.
 */
public enum CaptureMethod {

    /** Capture as soon as the provider authorizes. The default, and what most merchants want. */
    AUTOMATIC,

    /** Stop at AUTHORIZED and wait for an explicit capture call. */
    MANUAL;

    public static CaptureMethod parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Capture method cannot be null");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown capture method: " + value);
        }
    }
}
