package com.paymesh.identity.application;

import com.paymesh.identity.domain.RefreshToken;
import com.paymesh.identity.domain.SecurityEvent;
import com.paymesh.identity.domain.SecurityEventType;
import com.paymesh.identity.domain.User;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The session lifecycle: log in, rotate, log out. One service rather than three
 * because all three share the same token-minting and auditing steps, and splitting
 * them would mean duplicating those or introducing a fourth collaborator.
 *
 * <p>No {@code @Transactional} here, matching the rest of PayMesh: each repository
 * call commits on its own. Two consequences, both deliberate:
 * <ul>
 *   <li>Reuse detection revokes the family and <em>then</em> throws. Under one
 *       transaction the throw would roll the revocation back, which is exactly
 *       the wrong outcome -- the revocation is the security response, not a
 *       side effect of a successful request.</li>
 *   <li>Rotation revokes the old token before minting the new one, so a crash in
 *       between costs the caller a session rather than leaving two live tokens in
 *       a family. Failing closed is the safe direction for a credential.</li>
 * </ul>
 * ponytail: good enough while sessions are the only thing at stake. If refresh
 * ever has to be atomic, wrap the rotation in a transaction and move the
 * reuse-revocation into its own (REQUIRES_NEW) so the throw cannot undo it.
 */
public final class AuthenticationService {

    /**
     * A real BCrypt hash of a value nobody knows. When the email is unknown we
     * still run one verification against it, so a login attempt costs the same
     * whether or not the account exists. Without this, response time alone
     * enumerates registered addresses.
     */
    private static final String TIMING_EQUALIZER_HASH =
        "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityEventRepository securityEventRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenService accessTokenService;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    public AuthenticationService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        AccessTokenService accessTokenService,
        Duration refreshTokenTtl,
        Clock clock
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityEventRepository = securityEventRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenService = accessTokenService;
        this.refreshTokenTtl = refreshTokenTtl;
        this.clock = clock;
    }

    public IssuedTokens login(LoginCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Login Command cannot be null");
        }

        Instant now = Instant.now(clock);
        String email = User.normalizeEmail(command.email());
        String password = requirePassword(command.password());

        Optional<User> found = userRepository.findByEmail(email);

        if (found.isEmpty()) {
            passwordHasher.matches(password, TIMING_EQUALIZER_HASH);
            recordEvent(SecurityEventType.LOGIN_FAILED, email, command.ipAddress(), now);
            throw new InvalidCredentialsException();
        }

        User user = found.get();

        if (!passwordHasher.matches(password, user.passwordHash())) {
            recordEvent(
                SecurityEventType.LOGIN_FAILED, user.userId().value(), command.ipAddress(), now
            );
            throw new InvalidCredentialsException();
        }

        // Status is checked only after the password verifies. Checking it first
        // would let anyone discover which addresses are suspended.
        if (!user.canAuthenticate()) {
            recordEvent(
                SecurityEventType.LOGIN_FAILED, user.userId().value(), command.ipAddress(), now
            );
            throw new UserNotActiveException(user.status());
        }

        IssuedTokens tokens = issueSession(user, RefreshToken.newFamilyId(), now);

        recordEvent(
            SecurityEventType.LOGIN_SUCCEEDED, user.userId().value(), command.ipAddress(), now
        );

        return tokens;
    }

    /**
     * Spends a refresh token and mints its successor in the same family.
     *
     * <p>A token presented a second time has already been spent, which means it
     * leaked: the legitimate client holds the successor, so whoever is replaying
     * the old one either stole it or is a client whose successor was stolen. There
     * is no way to tell which, so the entire family is revoked and both parties
     * are forced back through login.
     */
    public IssuedTokens refresh(String refreshToken, String ipAddress) {
        Instant now = Instant.now(clock);

        RefreshToken presented = refreshTokenRepository
            .findByTokenHash(RefreshToken.hash(requireRefreshToken(refreshToken)))
            .orElseThrow(InvalidRefreshTokenException::new);

        if (presented.isRevoked()) {
            refreshTokenRepository.revokeFamily(presented.familyId(), now);

            recordEvent(
                SecurityEventType.REFRESH_TOKEN_REUSE_DETECTED,
                presented.userId().value(),
                ipAddress,
                now
            );

            throw new InvalidRefreshTokenException();
        }

        if (presented.isExpired(now)) {
            throw new InvalidRefreshTokenException();
        }

        // The account can be suspended or deleted while a session is live; a
        // refresh must not outlive it.
        User user = userRepository.findByUserId(presented.userId())
            .orElseThrow(InvalidRefreshTokenException::new);

        if (!user.canAuthenticate()) {
            refreshTokenRepository.revokeFamily(presented.familyId(), now);
            throw new InvalidRefreshTokenException();
        }

        refreshTokenRepository.save(presented.revoke(now));

        IssuedTokens tokens = issueSession(user, presented.familyId(), now);

        recordEvent(SecurityEventType.TOKEN_REFRESHED, user.userId().value(), ipAddress, now);

        return tokens;
    }

    /**
     * Ends the session the token belongs to, by revoking its whole family: the
     * caller may hold any token in the lineage, and leaving the others live would
     * make logout meaningless.
     *
     * <p>Idempotent. An unknown or already-revoked token is a no-op rather than an
     * error, so logout cannot be used to test whether a token is still valid, and
     * a client retrying after a timeout does not get a spurious failure.
     */
    public void logout(String refreshToken, String ipAddress) {
        Instant now = Instant.now(clock);

        refreshTokenRepository
            .findByTokenHash(RefreshToken.hash(requireRefreshToken(refreshToken)))
            .ifPresent(token -> {
                refreshTokenRepository.revokeFamily(token.familyId(), now);
                recordEvent(
                    SecurityEventType.LOGGED_OUT, token.userId().value(), ipAddress, now
                );
            });
    }

    private IssuedTokens issueSession(User user, String familyId, Instant now) {
        AccessTokenService.IssuedAccessToken accessToken = accessTokenService.issue(user, now);

        String plaintextRefreshToken = RefreshToken.newSecret();

        refreshTokenRepository.save(
            RefreshToken.issue(
                familyId,
                user.userId(),
                plaintextRefreshToken,
                now,
                now.plus(refreshTokenTtl)
            )
        );

        return new IssuedTokens(
            accessToken.value(),
            plaintextRefreshToken,
            Duration.between(now, accessToken.expiresAt()).toSeconds()
        );
    }

    private void recordEvent(
        SecurityEventType type,
        String actor,
        String ipAddress,
        Instant occurredAt
    ) {
        securityEventRepository.save(SecurityEvent.record(type, actor, ipAddress, occurredAt));
    }

    private static String requirePassword(String password) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password cannot be blank");
        }

        return password;
    }

    private static String requireRefreshToken(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("Refresh token cannot be blank");
        }

        return refreshToken;
    }
}
