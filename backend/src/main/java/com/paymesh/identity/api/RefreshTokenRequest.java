package com.paymesh.identity.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of both /auth/token/refresh and /auth/logout. One record because the two
 * carry the same single field and the same meaning -- the token identifying the
 * session being acted on.
 *
 * <p>The token travels in the body rather than an Authorization header because a
 * refresh token is not a bearer credential for the API: it is only ever presented
 * to these two endpoints, and keeping it out of headers keeps it out of proxy logs
 * that routinely record Authorization.
 */
public record RefreshTokenRequest(
    @NotBlank(message = "Refresh token is required")
    String refreshToken
) {
}
