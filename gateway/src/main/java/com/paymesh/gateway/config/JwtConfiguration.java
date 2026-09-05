package com.paymesh.gateway.config;

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
 * The edge verifier. It reconstructs the SAME HS256 decoder the monolith verifies with, from the
 * SAME shared secret ({@code paymesh.security.jwt.secret}), so a token minted by identity validates
 * identically here -- the gateway is not a second authority, it is the same check moved to the front.
 *
 * <p>Symmetric because the platform's tokens are HS256 (backend {@code JwtAccessTokenService}); a
 * shared secret is the cost of that choice, and edge validation needs it. When identity becomes a
 * service (3C) and the platform moves to asymmetric keys, this bean fetches a public key / JWKS
 * instead and the shared secret disappears -- the one line that changes.
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

        // Match the monolith's decoder (JwtAccessTokenService) exactly, so "the same check moved to
        // the front" is literally true and the two authorities never disagree on timing: zero clock
        // skew, and a token missing the exp claim is rejected rather than treated as non-expiring.
        // Nimbus's default validator allows 60s of skew and an empty exp -- both looser than the
        // monolith, which would let a token the monolith rejects pass the edge.
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(timestamps);
        return decoder;
    }
}
