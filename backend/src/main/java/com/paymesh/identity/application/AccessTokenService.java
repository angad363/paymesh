package com.paymesh.identity.application;

import com.paymesh.identity.domain.User;

import java.time.Instant;

/**
 * Issues and verifies short-lived access tokens (SDD 8.6). The JWT/JOSE library is
 * an infrastructure concern, so it stays behind this port.
 *
 * <p>Nothing in PayMesh enforces these tokens yet -- applying them to the merchant
 * API is a deliberately separate change. {@link #verify} exists now so that
 * issuing and verifying are proved against each other from the start.
 */
public interface AccessTokenService {

    IssuedAccessToken issue(User user, Instant issuedAt);

    /**
     * @throws InvalidAccessTokenException if the token is malformed, not signed by
     *                                     this service's key, or expired
     */
    AccessTokenClaims verify(String token);

    record IssuedAccessToken(String value, Instant expiresAt) {
    }
}
