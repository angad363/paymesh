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
