package com.paymesh.identity.application;

import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import com.paymesh.identity.domain.UserStatus;
import com.paymesh.identity.infrastructure.security.BCryptPasswordHasher;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-18T10:15:30Z");
    private static final Duration REFRESH_TTL = Duration.ofDays(30);
    private static final String EMAIL = "owner@freshbrew.example";
    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String IP = "203.0.113.7";

    private final Fakes.UserRepositoryFake users = new Fakes.UserRepositoryFake();
    private final Fakes.RefreshTokenRepositoryFake refreshTokens =
        new Fakes.RefreshTokenRepositoryFake();
    private final Fakes.SecurityEventRepositoryFake securityEvents =
        new Fakes.SecurityEventRepositoryFake();
    private final PasswordHasher passwordHasher = new BCryptPasswordHasher(4);

    private final AuthenticationService service = serviceAt(NOW);

    // --- login ---------------------------------------------------------------

    @Test
    void issuesBothTokensOnSuccessfulLogin() {
        User user = givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        assertEquals("access-token-for-" + user.userId().value(), tokens.accessToken());
        assertTrue(tokens.refreshToken() != null && !tokens.refreshToken().isBlank());
        assertEquals(Duration.ofMinutes(15).toSeconds(), tokens.expiresInSeconds());
        assertEquals(1, refreshTokens.liveTokens());
    }

    @Test
    void acceptsLoginWithADifferentlyCasedEmail() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(
            new LoginCommand("  Owner@FreshBrew.EXAMPLE ", PASSWORD, IP)
        );

        assertTrue(tokens.accessToken().startsWith("access-token-for-usr_"));
    }

    @Test
    void rejectsLoginWithTheWrongPassword() {
        givenRegisteredUser(UserStatus.ACTIVE);

        assertThrows(
            InvalidCredentialsException.class,
            () -> service.login(new LoginCommand(EMAIL, "wrong-password-entirely", IP))
        );

        assertEquals(0, refreshTokens.liveTokens());
        assertTrue(securityEvents.types().contains(SecurityEventType.LOGIN_FAILED));
    }

    @Test
    void rejectsLoginForAnUnknownEmail() {
        assertThrows(
            InvalidCredentialsException.class,
            () -> service.login(new LoginCommand("nobody@freshbrew.example", PASSWORD, IP))
        );

        assertEquals(List.of(SecurityEventType.LOGIN_FAILED), securityEvents.types());
    }

    /**
     * The unknown-email and wrong-password paths must be indistinguishable, or the
     * endpoint tells an attacker which addresses are registered.
     */
    @Test
    void reportsUnknownEmailAndWrongPasswordIdentically() {
        givenRegisteredUser(UserStatus.ACTIVE);

        String wrongPassword = assertThrows(
            InvalidCredentialsException.class,
            () -> service.login(new LoginCommand(EMAIL, "wrong-password-entirely", IP))
        ).getMessage();

        String unknownEmail = assertThrows(
            InvalidCredentialsException.class,
            () -> service.login(new LoginCommand("nobody@freshbrew.example", PASSWORD, IP))
        ).getMessage();

        assertEquals(wrongPassword, unknownEmail);
    }

    @Test
    void rejectsLoginForASuspendedAccount() {
        givenRegisteredUser(UserStatus.SUSPENDED);

        assertThrows(
            UserNotActiveException.class,
            () -> service.login(new LoginCommand(EMAIL, PASSWORD, IP))
        );

        assertEquals(0, refreshTokens.liveTokens());
    }

    /** Status is only consulted after the password verifies, so it cannot leak. */
    @Test
    void reportsWrongPasswordEvenWhenTheAccountIsSuspended() {
        givenRegisteredUser(UserStatus.SUSPENDED);

        assertThrows(
            InvalidCredentialsException.class,
            () -> service.login(new LoginCommand(EMAIL, "wrong-password-entirely", IP))
        );
    }

    @Test
    void rejectsBlankLoginPassword() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.login(new LoginCommand(EMAIL, "", IP))
        );
    }

    // --- refresh -------------------------------------------------------------

    @Test
    void rotatesTheRefreshTokenAndKeepsTheFamily() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens first = service.login(new LoginCommand(EMAIL, PASSWORD, IP));
        IssuedTokens second = service.refresh(first.refreshToken(), IP);

        assertNotEquals(first.refreshToken(), second.refreshToken());
        // The spent token is revoked and the successor is live: exactly one of each.
        assertEquals(1, refreshTokens.liveTokens());
        assertTrue(securityEvents.types().contains(SecurityEventType.TOKEN_REFRESHED));
    }

    @Test
    void rejectsAnUnknownRefreshToken() {
        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh("not-a-token-anyone-issued", IP)
        );
    }

    @Test
    void rejectsAMalformedRefreshToken() {
        assertThrows(
            IllegalArgumentException.class,
            () -> service.refresh("   ", IP)
        );
    }

    @Test
    void rejectsAnExpiredRefreshToken() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        AuthenticationService later = serviceAt(NOW.plus(REFRESH_TTL).plusSeconds(1));

        assertThrows(
            InvalidRefreshTokenException.class,
            () -> later.refresh(tokens.refreshToken(), IP)
        );
    }

    /**
     * The core anti-theft rule. Presenting a token that has already been rotated
     * means it leaked, so every token in the lineage dies -- including the live
     * successor the legitimate client is holding.
     */
    @Test
    void reusingARotatedRefreshTokenRevokesTheWholeFamily() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens first = service.login(new LoginCommand(EMAIL, PASSWORD, IP));
        IssuedTokens second = service.refresh(first.refreshToken(), IP);

        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh(first.refreshToken(), IP)
        );

        assertEquals(0, refreshTokens.liveTokens());
        assertTrue(
            securityEvents.types().contains(SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED)
        );

        // The successor the honest client still holds is dead too.
        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh(second.refreshToken(), IP)
        );
    }

    /** A session must not outlive the account it belongs to. */
    @Test
    void rejectsRefreshWhenTheAccountHasBeenSuspended() {
        User user = givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        users.save(
            User.reconstitute(
                user.userId(),
                user.email(),
                user.passwordHash(),
                UserStatus.SUSPENDED,
                user.roles(),
                user.createdAt(),
                user.updatedAt()
            )
        );

        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh(tokens.refreshToken(), IP)
        );

        assertEquals(0, refreshTokens.liveTokens());
    }

    @Test
    void rejectsRefreshWhenTheAccountHasBeenDeleted() {
        User user = givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        users.remove(user.userId());

        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh(tokens.refreshToken(), IP)
        );
    }

    // --- logout --------------------------------------------------------------

    @Test
    void logoutRevokesTheSessionSoRefreshFails() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        service.logout(tokens.refreshToken(), IP);

        assertEquals(0, refreshTokens.liveTokens());
        assertTrue(securityEvents.types().contains(SecurityEventType.LOGGED_OUT));

        assertThrows(
            InvalidRefreshTokenException.class,
            () -> service.refresh(tokens.refreshToken(), IP)
        );
    }

    /** Logging out of one session must not end the others. */
    @Test
    void logoutRevokesOnlyTheFamilyItWasGiven() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens firstSession = service.login(new LoginCommand(EMAIL, PASSWORD, IP));
        IssuedTokens secondSession = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        service.logout(firstSession.refreshToken(), IP);

        assertEquals(1, refreshTokens.liveTokens());
        assertNotEquals(
            secondSession.refreshToken(),
            service.refresh(secondSession.refreshToken(), IP).refreshToken()
        );
    }

    /** Idempotent, so it cannot be used to probe whether a token is still live. */
    @Test
    void logoutIsSilentForAnUnknownToken() {
        service.logout("not-a-token-anyone-issued", IP);

        assertEquals(List.of(), securityEvents.types());
    }

    @Test
    void logoutIsSilentWhenRepeated() {
        givenRegisteredUser(UserStatus.ACTIVE);

        IssuedTokens tokens = service.login(new LoginCommand(EMAIL, PASSWORD, IP));

        service.logout(tokens.refreshToken(), IP);
        service.logout(tokens.refreshToken(), IP);

        assertEquals(0, refreshTokens.liveTokens());
    }

    // --- helpers -------------------------------------------------------------

    private AuthenticationService serviceAt(Instant instant) {
        return new AuthenticationService(
            users,
            refreshTokens,
            securityEvents,
            passwordHasher,
            new Fakes.AccessTokenServiceFake(),
            REFRESH_TTL,
            Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private User givenRegisteredUser(UserStatus status) {
        User user = User.reconstitute(
            UserId.generate(),
            EMAIL,
            passwordHasher.hash(PASSWORD),
            status,
            List.of(),
            NOW,
            NOW
        );

        return users.save(user);
    }
}
