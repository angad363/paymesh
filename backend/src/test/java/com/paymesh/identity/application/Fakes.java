package com.paymesh.identity.application;

import com.paymesh.identity.domain.RefreshToken;
import com.paymesh.identity.domain.SecurityEvent;
import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory stand-ins for the identity ports, shared by the application tests so
 * they stay plain JUnit with no Spring context and no database.
 *
 * <p>There is no PasswordHasher fake: the real BCryptPasswordHasher is an ordinary
 * object, and using it keeps the tests honest about the hash format the User
 * aggregate insists on.
 */
final class Fakes {

    private Fakes() {
    }

    static final class UserRepositoryFake implements UserRepository {

        private final Map<String, User> byId = new LinkedHashMap<>();

        @Override
        public java.util.List<User> findByMerchant(com.paymesh.shared.tenant.MerchantId merchantId) {
            return byId.values().stream()
                .filter(user -> user.hasRoleAt(merchantId.value()))
                .toList();
        }

        @Override
        public boolean existsByEmail(String normalizedEmail) {
            return findByEmail(normalizedEmail).isPresent();
        }

        @Override
        public User save(User user) {
            byId.put(user.userId().value(), user);
            return user;
        }

        @Override
        public Optional<User> findByEmail(String normalizedEmail) {
            return byId.values().stream()
                .filter(user -> user.email().equals(normalizedEmail))
                .findFirst();
        }

        @Override
        public Optional<User> findByUserId(UserId userId) {
            return Optional.ofNullable(byId.get(userId.value()));
        }

        void remove(UserId userId) {
            byId.remove(userId.value());
        }

        List<User> saved() {
            return List.copyOf(byId.values());
        }
    }

    static final class RefreshTokenRepositoryFake implements RefreshTokenRepository {

        private final Map<String, RefreshToken> byHash = new LinkedHashMap<>();

        @Override
        public RefreshToken save(RefreshToken refreshToken) {
            byHash.put(refreshToken.tokenHash(), refreshToken);
            return refreshToken;
        }

        @Override
        public Optional<RefreshToken> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        /**
         * Mirrors the SQL: revokes only if still live, and reports whether this call
         * was the one that did it. Tests can force a lost race by pre-revoking.
         */
        @Override
        public int revokeIfLive(String tokenHash, Instant revokedAt) {
            RefreshToken token = byHash.get(tokenHash);

            if (token == null || token.isRevoked()) {
                return 0;
            }

            byHash.put(tokenHash, token.revoke(revokedAt));

            return 1;
        }

        @Override
        public int revokeAllForUser(
            com.paymesh.identity.domain.UserId userId, Instant revokedAt
        ) {
            int revoked = 0;

            for (Map.Entry<String, RefreshToken> entry : byHash.entrySet()) {
                RefreshToken token = entry.getValue();

                if (token.userId().equals(userId) && !token.isRevoked()) {
                    entry.setValue(token.revoke(revokedAt));
                    revoked++;
                }
            }

            return revoked;
        }

        @Override
        public int revokeFamily(String familyId, Instant revokedAt) {
            int revoked = 0;

            for (Map.Entry<String, RefreshToken> entry : byHash.entrySet()) {
                RefreshToken token = entry.getValue();

                if (token.familyId().equals(familyId) && !token.isRevoked()) {
                    entry.setValue(token.revoke(revokedAt));
                    revoked++;
                }
            }

            return revoked;
        }

        long liveTokens() {
            return byHash.values().stream().filter(token -> !token.isRevoked()).count();
        }
    }

    static final class SecurityEventRepositoryFake implements SecurityEventRepository {

        private final List<SecurityEvent> events = new ArrayList<>();

        @Override
        public void save(SecurityEvent securityEvent) {
            events.add(securityEvent);
        }

        List<SecurityEventType> types() {
            return events.stream().map(SecurityEvent::type).toList();
        }

        List<SecurityEvent> events() {
            return List.copyOf(events);
        }
    }

    /**
     * Issues a predictable token so assertions can name it. Signing is covered
     * against the real implementation in JwtAccessTokenServiceTest.
     */
    static final class AccessTokenServiceFake implements AccessTokenService {

        private final java.time.Duration ttl = java.time.Duration.ofMinutes(15);

        @Override
        public IssuedAccessToken issue(User user, Instant issuedAt) {
            return new IssuedAccessToken(
                "access-token-for-" + user.userId().value(),
                issuedAt.plus(ttl)
            );
        }

        @Override
        public AccessTokenClaims verify(String token) {
            throw new UnsupportedOperationException("Not needed by these tests");
        }
    }
}
