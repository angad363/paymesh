package com.paymesh.simulator.domain;

import java.util.Locale;

/**
 * Whether the provider captures on authorization or waits to be asked.
 * <p>
 * This is what lets the simulator exercise PayMesh's manual-capture path: MANUAL emits an
 * AUTHORIZED callback and stops, and {@code POST /sim/v1/payments/{id}/capture} is what later emits
 * the SUCCEEDED one. ADR-012 section 4 is explicit that a provider may not capture on its own say-so
 * -- AUTHORIZED to SUCCEEDED is refused as a provider transition -- so a simulator that captured
 * automatically on a MANUAL intent would produce an IGNORED_TERMINAL and look broken.
 */
public enum SimulatedCaptureMethod {

    AUTOMATIC,
    MANUAL;

    public static SimulatedCaptureMethod parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Capture method cannot be blank");
        }

        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown capture method: " + value);
        }
    }
}
