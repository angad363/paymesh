package com.paymesh.identity.application;

import com.paymesh.identity.domain.RefreshToken;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes every still-live token in a rotation family, in one statement.
     * Called on logout and on detected reuse -- the case where a row-by-row loop
     * would be a race against the attacker.
     *
     * @return how many tokens were revoked
     */
    int revokeFamily(String familyId, Instant revokedAt);
}
