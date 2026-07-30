package com.paymesh.identity.infrastructure.security;

import com.paymesh.identity.application.AccessTokenClaims;
import com.paymesh.identity.application.AccessTokenService;
import com.paymesh.identity.application.InvalidAccessTokenException;
import com.paymesh.identity.domain.RoleAssignment;
import com.paymesh.identity.domain.User;
import com.paymesh.identity.domain.UserId;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * HS256 JWTs, signed with a shared secret from configuration.
 *
 * <p>HMAC rather than RSA/EC because every party that verifies these tokens today
 * is this same deployable. The day an independently deployed service has to verify
 * them without being able to mint them, this becomes asymmetric signing plus a
 * JWKS endpoint -- swap the builders below and nothing outside this class changes.
 *
 * <p>Verification only ever accepts HS256 with this one key, so the classic "alg"
 * confusion attacks (alg: none, or an RS256 token verified with the public key as
 * an HMAC secret) cannot apply.
 */
public final class JwtAccessTokenService implements AccessTokenService {

    private static final String ISSUER = "paymesh";
    private static final String EMAIL_CLAIM = "email";
    private static final String ROLES_CLAIM = "roles";

    /**
     * HS256 requires a key at least as long as its output. A shorter secret is a
     * configuration mistake that must stop the application at startup, not weaken
     * every token it issues.
     */
    private static final int MINIMUM_SECRET_BYTES = 32;

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final Duration accessTokenTtl;

    public JwtAccessTokenService(String secret, Duration accessTokenTtl, Clock clock) {
        SecretKey key = secretKey(secret);

        this.encoder = NimbusJwtEncoder.withSecretKey(key)
            .algorithm(MacAlgorithm.HS256)
            .build();

        this.decoder = decoder(key, clock);
        this.accessTokenTtl = accessTokenTtl;
    }

    /**
     * The decoder this service verifies with, so the filter chain can share it instead of building
     * a second one from the same secret. Exposed on the class, not on the AccessTokenService port,
     * which stays free of Spring Security types.
     */
    public NimbusJwtDecoder decoder() {
        return decoder;
    }

    @Override
    public IssuedAccessToken issue(User user, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(accessTokenTtl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(user.userId().value())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim(EMAIL_CLAIM, user.email())
            .claim(ROLES_CLAIM, scopedRoles(user))
            .build();

        Jwt jwt = encoder.encode(JwtEncoderParameters.from(claims));

        return new IssuedAccessToken(jwt.getTokenValue(), expiresAt);
    }

    @Override
    public AccessTokenClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException("Access token cannot be blank");
        }

        try {
            Jwt jwt = decoder.decode(token);

            return new AccessTokenClaims(
                UserId.from(jwt.getSubject()),
                jwt.getClaimAsString(EMAIL_CLAIM),
                jwt.getClaimAsStringList(ROLES_CLAIM),
                jwt.getIssuedAt(),
                jwt.getExpiresAt()
            );
        } catch (JwtException exception) {
            // One exception for malformed, wrongly-signed and expired alike: a caller
            // can only react one way, and the detail belongs in logs, not a response.
            throw new InvalidAccessTokenException("Access token is invalid or has expired", exception);
        }
    }

    /**
     * Roles travel as {@code "<ROLE>:<merchantId>"} rather than a single merchant
     * claim, because one user can hold roles at several merchants and the scope has
     * to stay attached to the role it qualifies.
     */
    private static List<String> scopedRoles(User user) {
        return user.roles().stream()
            .map(JwtAccessTokenService::scopedRole)
            .toList();
    }

    private static String scopedRole(RoleAssignment assignment) {
        return assignment.role().name() + ":" + assignment.merchantId();
    }

    private static SecretKey secretKey(String secret) {
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret cannot be null");
        }

        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalArgumentException(
                "JWT secret must be at least " + MINIMUM_SECRET_BYTES + " bytes for HS256"
            );
        }

        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    private static NimbusJwtDecoder decoder(SecretKey key, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();

        // Zero clock skew, and the injected Clock, so expiry is exactly as
        // deterministic in a test as the services around it.
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator(Duration.ZERO);
        timestampValidator.setClock(clock);
        timestampValidator.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(timestampValidator);

        return decoder;
    }
}
