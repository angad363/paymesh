package com.paymesh.identity.api;

import com.paymesh.identity.application.IssuedTokens;

/**
 * The credential pair returned by login and refresh.
 *
 * <p>{@code expiresIn} is the access token's remaining lifetime in seconds (the
 * OAuth 2.0 convention), not an absolute timestamp: a client's clock may be wrong,
 * and a duration is immune to that.
 *
 * <p>This is the only place the refresh token's plaintext ever appears. It is
 * hashed before storage and cannot be retrieved afterwards.
 */
public record TokenResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    long expiresIn
) {
    public static TokenResponse from(IssuedTokens tokens) {
        return new TokenResponse(
            tokens.accessToken(),
            tokens.refreshToken(),
            "Bearer",
            tokens.expiresInSeconds()
        );
    }
}
