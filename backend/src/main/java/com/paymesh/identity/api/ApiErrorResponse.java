package com.paymesh.identity.api;

import java.util.Map;

/**
 * The identity module's copy of the flat error body the merchant API already
 * returns. Duplicated rather than imported: api packages are the module boundary,
 * and identity importing com.paymesh.merchant.api would couple two capabilities
 * that are meant to be separately extractable (ADR-001/ADR-002).
 *
 * <p>When a third capability needs the same shape, that is the signal to promote
 * one copy into com.paymesh.shared and delete the rest.
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
