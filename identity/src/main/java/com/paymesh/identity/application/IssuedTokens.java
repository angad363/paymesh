package com.paymesh.identity.application;

/**
 * The credential pair a caller receives from login and refresh.
 *
 * <p>{@code refreshToken} is the only place the plaintext ever exists -- it is
 * hashed before it reaches the database and cannot be retrieved afterwards.
 */
public record IssuedTokens(
    String accessToken,
    String refreshToken,
    long expiresInSeconds
) {
}
