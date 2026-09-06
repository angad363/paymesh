package com.paymesh.webhook.infrastructure.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Verifies the SAME HS256 token the monolith issues and the gateway re-validates at the edge
 * (ADR-040), from the SAME shared secret ({@code paymesh.security.jwt.secret}) -- webhook is not a
 * third authority, it is the same check carried across the process boundary (ADR-042 section 3).
 * <p>
 * In the monolith this bean comes from {@code IdentityConfiguration}, wrapping the same
 * {@code JwtAccessTokenService} that mints tokens -- unavailable here, since Identity has not been
 * extracted (that is PR 10). This is instead a byte-for-byte copy of the gateway's own
 * {@code JwtConfiguration}: the same reconstruction from a shared secret, needed for exactly the
 * reason the gateway's javadoc gives -- webhook validates independently, defense in depth, and
 * neither is a second authority.
 */
@Configuration
public class JwtConfiguration {

    /** HS256 needs a key at least as long as its 256-bit output; the monolith enforces the same floor. */
    private static final int MINIMUM_SECRET_BYTES = 32;

    @Bean
    JwtDecoder jwtDecoder(@Value("${paymesh.security.jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                "JWT secret must be at least " + MINIMUM_SECRET_BYTES + " bytes for HS256");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder
            .withSecretKey(new SecretKeySpec(keyBytes, "HmacSHA256"))
            .macAlgorithm(MacAlgorithm.HS256)
            .build();

        // Match the monolith's decoder (JwtAccessTokenService) exactly, the same reason the
        // gateway's copy of this bean gives: zero clock skew, and a token missing the exp claim is
        // rejected rather than treated as non-expiring.
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(timestamps);
        return decoder;
    }
}
