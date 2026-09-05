package com.paymesh.gateway;

/**
 * The house error shape, {@code {code, message}} -- the same flat body the monolith returns from its
 * security chain (see backend {@code shared.api.ApiErrorResponse}). Copied, not shared: the gateway
 * has no dependency on the monolith's jar and must not grow one for two fields. A client that already
 * parses the monolith's 401 parses the gateway's identically.
 */
public record ApiErrorResponse(String code, String message) {

    public static ApiErrorResponse of(String code, String message) {
        return new ApiErrorResponse(code, message);
    }
}
