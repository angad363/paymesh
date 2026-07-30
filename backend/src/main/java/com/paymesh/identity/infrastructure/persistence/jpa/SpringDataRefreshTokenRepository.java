package com.paymesh.identity.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface SpringDataRefreshTokenRepository
    extends JpaRepository<RefreshTokenJpaEntity, String> {

    /**
     * Spends a single token, atomically. {@code revokedAt is null} in the WHERE
     * clause is what makes rotation safe under concurrency: the database decides
     * which of several racing callers actually spent the token, and the rest get 0.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update RefreshTokenJpaEntity token
           set token.revokedAt = :revokedAt
         where token.tokenHash = :tokenHash
           and token.revokedAt is null
        """)
    int revokeIfLive(@Param("tokenHash") String tokenHash, @Param("revokedAt") Instant revokedAt);

    /**
     * Revokes a whole rotation family in one statement. A row-by-row loop would be
     * a race against whoever stole the token.
     *
     * <p>{@code revoked_at IS NULL} keeps the first revocation instant: that is when
     * the session actually ended, and reprocessing must not move it.
     *
     * <p>{@code clearAutomatically}/{@code flushAutomatically} because a bulk update
     * bypasses the persistence context -- without them a token already loaded in the
     * current transaction would still look live.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update RefreshTokenJpaEntity token
           set token.revokedAt = :revokedAt
         where token.familyId = :familyId
           and token.revokedAt is null
        """)
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") Instant revokedAt);
}
