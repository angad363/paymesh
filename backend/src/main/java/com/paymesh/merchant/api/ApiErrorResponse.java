package com.paymesh.merchant.api;

import java.util.Map;

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
