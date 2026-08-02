package com.paymesh.identity.application;

import com.paymesh.identity.domain.RefreshToken;
import com.paymesh.identity.domain.UserId;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository {

    RefreshToken save(RefreshToken refreshToken);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes one token only if it is still live, atomically.
     *
     * <p>This is a compare-and-swap, and rotation depends on it. Reading a token,
     * finding it unrevoked and then writing the revocation is two statements with a
     * gap: two requests presenting the same token can both pass the check and both
     * mint a successor, leaving two live tokens in one family and no reuse detected.
     * That is precisely the case rotation exists to catch.
     *
     * <p>Pushing the condition into the UPDATE makes the database the arbiter.
     * Exactly one caller observes a non-zero result; every other caller learns it
     * lost, which is indistinguishable from replay and must be treated as such.
     *
     * @return 1 if this call revoked the token, 0 if it was already revoked
     */
    int revokeIfLive(String tokenHash, Instant revokedAt);

    /**
     * Revokes every still-live token in a rotation family, in one statement.
     * Called on logout and on detected reuse -- the case where a row-by-row loop
     * would be a race against the attacker.
     *
     * @return how many tokens were revoked
     */
    int revokeFamily(String familyId, Instant revokedAt);

    /**
     * Ends every live session this user has, across all families.
     * <p>
     * Used when the account is suspended or closed, or loses its last relevant role. Broader than
     * {@link #revokeFamily} on purpose: a suspension is about the person, not about one suspicious
     * chain of tokens.
     */
    int revokeAllForUser(UserId userId, Instant revokedAt);
}
