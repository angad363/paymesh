package com.paymesh.identity.infrastructure.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The access-token signing configuration, bound and validated once at startup.
 * <p>
 * A record rather than scattered {@code @Value} lookups because binding is where a missing
 * value can still be named. Read lazily per injection point, an absent secret surfaces as a
 * NullPointerException from inside the token service, long after the operator who forgot the
 * environment variable could act on it; validated here, startup stops with the property name in
 * the message.
 * <p>
 * The secret being present is not the same question as the secret being safe -- see
 * {@code DevelopmentSecretGuard} for provenance and JwtAccessTokenService for key length.
 */
@Validated
@ConfigurationProperties("paymesh.security.jwt")
public record JwtProperties(

    @NotBlank
    String secret,

    @NotNull
    Duration accessTokenTtl,

    @NotNull
    Duration refreshTokenTtl
) {
}
