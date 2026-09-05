package com.paymesh.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Shared rig for the gateway integration tests: a real Redis (the rate limiter's store) and a
 * WireMock standing in for the monolith backend, both started once for the JVM. Subclasses point the
 * gateway's {@code backend-uri} and {@code redis-uri} at these via {@link DynamicPropertySource}.
 *
 * <p>The dev profile supplies the same HS256 secret the tokens here are minted with, so a token this
 * base creates validates at the gateway edge exactly as a real one from identity would.
 */
abstract class GatewayIntegrationTestBase {

    /** Byte-for-byte the dev secret (application-dev.yaml), so minted tokens validate at the edge. */
    protected static final String DEV_SECRET = "dev-only-insecure-jwt-signing-secret-change-me";

    protected static final GenericContainer<?> REDIS =
        new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    protected static final WireMockServer BACKEND = new WireMockServer(options().dynamicPort());

    static {
        REDIS.start();
        BACKEND.start();
    }

    @LocalServerPort
    protected int gatewayPort;

    /**
     * Start every test from an empty rate-limit store. The two test classes share one Redis and key
     * buckets by IP, so without this the capacity-100 bucket one class creates for {@code 127.0.0.1}
     * would be the stale bucket the capacity-3 class then reads -- a cross-context collision that has
     * nothing to do with the gateway and everything to do with sharing a real store across contexts.
     */
    @org.junit.jupiter.api.BeforeEach
    void flushRateLimitStore() throws Exception {
        REDIS.execInContainer("redis-cli", "FLUSHALL");
    }

    @DynamicPropertySource
    static void wireBackendAndRedis(DynamicPropertyRegistry registry) {
        registry.add("paymesh.gateway.backend-uri", () -> "http://localhost:" + BACKEND.port());
        registry.add("paymesh.gateway.redis-uri",
            () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    protected String gatewayUrl(String path) {
        return "http://localhost:" + gatewayPort + path;
    }

    /** A valid, unexpired HS256 token signed with the dev secret. The gateway checks only that. */
    protected static String validToken() {
        SecretKeySpec key = new SecretKeySpec(DEV_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
            .subject("usr_11111111-1111-4111-8111-111111111111")
            .issuedAt(now)
            .expiresAt(now.plus(Duration.ofMinutes(15)))
            .claim("roles", List.of("MERCHANT_ADMIN:mrc_00000000-0000-4000-8000-000000000001"))
            .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
