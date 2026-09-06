package com.paymesh.simulator.api;

import java.util.Map;

/**
 * This module's own copy of the {@code {code, message, fieldErrors}} error shape, byte-for-byte
 * the same record {@code com.paymesh.shared.api.ApiErrorResponse} defines in the monolith.
 * <p>
 * Duplicated rather than shared, for the same reason {@code CallbackBody} restates
 * {@code ProviderCallbackRequest} instead of importing it (ADR-017): this module holds zero
 * references to PayMesh in either direction, and a shared type is exactly the coupling that
 * would make the two one deployable by definition. The gateway made the identical call for its
 * own {@code ApiErrorResponse} (ADR-040).
 */
public record ApiErrorResponse(
    String code,
    String message,
    Map<String, String> fieldErrors
) {
    public static ApiErrorResponse of(
        String code,
        String message
    ) {
        return new ApiErrorResponse(
            code,
            message,
            Map.of()
        );
    }

    public static ApiErrorResponse validation(
        Map<String, String> fieldErrors
    ) {
        return new ApiErrorResponse(
            "VALIDATION_FAILED",
            "Request validation failed.",
            fieldErrors
        );
    }
}
