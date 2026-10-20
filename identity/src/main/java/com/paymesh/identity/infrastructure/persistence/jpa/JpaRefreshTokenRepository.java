package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.identity.application.RefreshTokenRepository;
import com.paymesh.identity.domain.RefreshToken;
import com.paymesh.identity.domain.UserId;

import java.time.Instant;
import java.util.Optional;

/** PostgreSQL-backed implementation of the application's RefreshTokenRepository port. */
public final class JpaRefreshTokenRepository implements RefreshTokenRepository {

    private final SpringDataRefreshTokenRepository refreshTokens;

    public JpaRefreshTokenRepository(SpringDataRefreshTokenRepository refreshTokens) {
        this.refreshTokens = refreshTokens;
    }

    @Override
    public RefreshToken save(RefreshToken refreshToken) {
        return RefreshTokenJpaMapper.toDomain(
            refreshTokens.saveAndFlush(RefreshTokenJpaMapper.toEntity(refreshToken))
        );
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return refreshTokens.findById(tokenHash).map(RefreshTokenJpaMapper::toDomain);
    }

    @Override
    public int revokeIfLive(String tokenHash, Instant revokedAt) {
        return refreshTokens.revokeIfLive(tokenHash, revokedAt);
    }

    @Override
    public int revokeAllForUser(UserId userId, Instant revokedAt) {
        return refreshTokens.revokeAllForUser(userId.value(), revokedAt);
    }

    @Override
    public int revokeFamily(String familyId, Instant revokedAt) {
        return refreshTokens.revokeFamily(familyId, revokedAt);
    }
}
