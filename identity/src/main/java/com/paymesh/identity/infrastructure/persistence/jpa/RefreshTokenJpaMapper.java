package com.paymesh.identity.infrastructure.persistence.jpa;

import com.paymesh.identity.domain.RefreshToken;
import com.paymesh.identity.domain.UserId;

/** Translates between the RefreshToken domain type and its row (ADR-004). */
public final class RefreshTokenJpaMapper {

    private RefreshTokenJpaMapper() {
    }

    public static RefreshTokenJpaEntity toEntity(RefreshToken refreshToken) {
        return new RefreshTokenJpaEntity(
            refreshToken.tokenHash(),
            refreshToken.familyId(),
            refreshToken.userId().value(),
            refreshToken.issuedAt(),
            refreshToken.expiresAt(),
            refreshToken.revokedAt()
        );
    }

    public static RefreshToken toDomain(RefreshTokenJpaEntity entity) {
        return RefreshToken.reconstitute(
            // Postgres pads CHAR(n) values on read; a trailing-space difference
            // would break hash equality checks in the domain.
            entity.tokenHash().trim(),
            entity.familyId().trim(),
            UserId.from(entity.userId()),
            entity.issuedAt(),
            entity.expiresAt(),
            entity.revokedAt()
        );
    }
}
