package com.paymesh.identity.infrastructure.security;

import com.paymesh.identity.application.AccessTokenClaims;
import com.paymesh.identity.application.AccessTokenService;
import com.paymesh.identity.application.InvalidAccessTokenException;
import com.paymesh.identity.domain.Role;
import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtAccessTokenServiceTest {

    private static final String SECRET = "a-test-signing-secret-that-is-long-enough";
    private static final String OTHER_SECRET = "a-different-secret-that-is-also-long-enough";
    private static final Instant NOW = Instant.parse("2026-07-18T10:15:30Z");
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final String MERCHANT_ID = "mrc_550e8400-e29b-41d4-a716-446655440000";
    private static final String BCRYPT_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final JwtAccessTokenService service = serviceAt(SECRET, NOW);

    @Test
    void issuedTokenVerifiesBackToItsClaims() {
        User user = user(List.of(new RoleAssignment(Role.MERCHANT_ADMIN, MERCHANT_ID)));

        AccessTokenService.IssuedAccessToken issued = service.issue(user, NOW);
        AccessTokenClaims claims = service.verify(issued.value());

        assertEquals(user.userId(), claims.userId());
        assertEquals(user.email(), claims.email());
        assertEquals(List.of("MERCHANT_ADMIN:" + MERCHANT_ID), claims.scopedRoles());
        assertEquals(NOW, claims.issuedAt());
        assertEquals(NOW.plus(TTL), claims.expiresAt());
    }

    @Test
    void expiresAfterTheConfiguredLifetime() {
        assertEquals(NOW.plus(TTL), service.issue(user(List.of()), NOW).expiresAt());
    }

    @Test
    void carriesEveryScopedRole() {
        User user = user(List.of(
            new RoleAssignment(Role.MERCHANT_ADMIN, MERCHANT_ID),
            new RoleAssignment(Role.MERCHANT_USER, "mrc_00000000-0000-4000-8000-000000000000")
        ));

        AccessTokenClaims claims = service.verify(service.issue(user, NOW).value());

        assertEquals(
            List.of(
                "MERCHANT_ADMIN:" + MERCHANT_ID,
                "MERCHANT_USER:mrc_00000000-0000-4000-8000-000000000000"
            ),
            claims.scopedRoles()
        );
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = service.issue(user(List.of()), NOW).value();

        JwtAccessTokenService later = serviceAt(SECRET, NOW.plus(TTL).plusSeconds(1));

        assertThrows(InvalidAccessTokenException.class, () -> later.verify(token));
    }

    @Test
    void acceptsATokenRightUpToItsExpiry() {
        String token = service.issue(user(List.of()), NOW).value();

        JwtAccessTokenService justBefore = serviceAt(SECRET, NOW.plus(TTL).minusSeconds(1));

        assertEquals(user(List.of()).email(), justBefore.verify(token).email());
    }

    /** A token signed with another key must not verify -- that is the whole point. */
    @Test
    void rejectsATokenSignedWithADifferentSecret() {
        String token = serviceAt(OTHER_SECRET, NOW).issue(user(List.of()), NOW).value();

        assertThrows(InvalidAccessTokenException.class, () -> service.verify(token));
    }

    @Test
    void rejectsATokenWhosePayloadWasTamperedWith() {
        String token = service.issue(user(List.of()), NOW).value();
        String[] parts = token.split("\\.");

        // Same header and signature, a payload that is no longer what was signed.
        String tampered = parts[0] + "." + parts[1].substring(1) + "x." + parts[2];

        assertThrows(InvalidAccessTokenException.class, () -> service.verify(tampered));
    }

    @Test
    void rejectsAMalformedToken() {
        assertThrows(InvalidAccessTokenException.class, () -> service.verify("not-a-jwt"));
    }

    @Test
    void rejectsABlankToken() {
        assertThrows(InvalidAccessTokenException.class, () -> service.verify("   "));
    }

    @Test
    void rejectsANullToken() {
        assertThrows(InvalidAccessTokenException.class, () -> service.verify(null));
    }

    /** HS256 needs at least 256 bits of key; a short secret must fail at startup. */
    @Test
    void rejectsASecretTooShortForHs256() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new JwtAccessTokenService("too-short", TTL, Clock.fixed(NOW, ZoneOffset.UTC))
        );
    }

    private static JwtAccessTokenService serviceAt(String secret, Instant instant) {
        return new JwtAccessTokenService(secret, TTL, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static User user(List<RoleAssignment> roles) {
        return User.reconstitute(
            UserId.from("usr_550e8400-e29b-41d4-a716-446655440000"),
            "owner@freshbrew.example",
            BCRYPT_HASH,
            com.paymesh.identity.domain.UserStatus.ACTIVE,
            roles,
            NOW,
            NOW
        );
    }
}
