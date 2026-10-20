package com.paymesh.identity.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * One issued refresh token. Tokens are opaque random strings rather than JWTs:
 * they are only ever presented back to the service that minted them, so there is
 * nothing for a signature to prove, and an opaque token can be revoked by
 * deleting a row instead of maintaining a denylist.
 *
 * <p>Tokens rotate. Spending one revokes it and mints a successor sharing its
 * {@code familyId}. A second attempt to spend an already-revoked token means the
 * token leaked -- either the attacker or the legitimate client is replaying -- and
 * the correct response is to revoke the whole family, because there is no way to
 * tell which of the two is holding the live successor.
 *
 * <p>The instance never holds the plaintext. {@link #issue} takes it, hashes it,
 * and forgets it; the plaintext exists only in the HTTP response to the caller.
 */
public final class RefreshToken {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final String tokenHash;
    private final String familyId;
    private final UserId userId;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private final Instant revokedAt;

    private RefreshToken(
        String tokenHash,
        String familyId,
        UserId userId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
    ) {
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.userId = userId;
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
    }

    /**
     * Mints the plaintext a caller receives: 256 bits of {@link SecureRandom},
     * base64url-encoded without padding so it is safe in headers and JSON.
     * 256 bits is the point where guessing stops being a threat model.
     */
    public static String newSecret() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Starts a new rotation family. One per successful login. */
    public static String newFamilyId() {
        return UUID.randomUUID().toString();
    }

    public static RefreshToken issue(
        String familyId,
        UserId userId,
        String plaintextToken,
        Instant issuedAt,
        Instant expiresAt
    ) {
        if (familyId == null || familyId.isBlank()) {
            throw new IllegalArgumentException("Refresh token family cannot be blank");
        }

        if (userId == null) {
            throw new IllegalArgumentException("User Identifier cannot be null");
        }

        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token cannot be blank");
        }

        if (issuedAt == null || expiresAt == null) {
            throw new IllegalArgumentException("Refresh token timestamps cannot be null");
        }

        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("Refresh token must expire after it is issued");
        }

        return new RefreshToken(
            hash(plaintextToken),
            familyId,
            userId,
            issuedAt,
            expiresAt,
            null
        );
    }

    /** Rebuilds a token from a persisted row (ADR-004). */
    public static RefreshToken reconstitute(
        String tokenHash,
        String familyId,
        UserId userId,
        Instant issuedAt,
        Instant expiresAt,
        Instant revokedAt
    ) {
        return new RefreshToken(tokenHash, familyId, userId, issuedAt, expiresAt, revokedAt);
    }

    /** The lookup key. Deterministic, so the presented plaintext finds its row. */
    public static String hash(String plaintextToken) {
        return Sha256.hex(plaintextToken);
    }

    /**
     * Marks the token spent or withdrawn. Returns a new instance rather than
     * mutating, matching the immutable-aggregate style used across PayMesh.
     * Revoking an already-revoked token keeps the original instant: the first
     * revocation is the one that says when the session actually ended.
     */
    public RefreshToken revoke(Instant revokedAt) {
        if (revokedAt == null) {
            throw new IllegalArgumentException("Revocation timestamp cannot be null");
        }

        if (this.revokedAt != null) {
            return this;
        }

        return new RefreshToken(tokenHash, familyId, userId, issuedAt, expiresAt, revokedAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** A token may be spent exactly once, before it expires. */
    public boolean isSpendable(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public String tokenHash() {
        return tokenHash;
    }

    public String familyId() {
        return familyId;
    }

    public UserId userId() {
        return userId;
    }

    public Instant issuedAt() {
        return issuedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
