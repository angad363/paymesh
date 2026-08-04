package com.paymesh.identity.infrastructure.config;

import com.paymesh.identity.application.AccessTokenService;
import com.paymesh.identity.application.AuthenticationService;
import com.paymesh.identity.application.PasswordHasher;
import com.paymesh.identity.application.RefreshTokenRepository;
import com.paymesh.identity.application.ManageUserAccessService;
import com.paymesh.identity.application.RegisterUserService;
import com.paymesh.identity.application.SecurityEventRepository;
import com.paymesh.identity.application.UserRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.JpaRefreshTokenRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.JpaSecurityEventRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.JpaUserRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.SpringDataRefreshTokenRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.SpringDataSecurityEventRepository;
import com.paymesh.identity.infrastructure.persistence.jpa.SpringDataUserRepository;
import com.paymesh.identity.infrastructure.security.BCryptPasswordHasher;
import com.paymesh.identity.infrastructure.security.JwtAccessTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Clock;

/**
 * Explicit wiring for the identity module. Application and domain classes carry no
 * Spring annotations; they are plain objects assembled here, which is what keeps
 * them testable without a container.
 *
 * <p>The Clock bean is not declared here: SharedConfiguration already provides
 * one and a second would be an ambiguous-bean failure at startup.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class IdentityConfiguration {

    @Bean
    UserRepository userRepository(SpringDataUserRepository springDataUserRepository) {
        return new JpaUserRepository(springDataUserRepository);
    }

    @Bean
    RefreshTokenRepository refreshTokenRepository(
        SpringDataRefreshTokenRepository springDataRefreshTokenRepository
    ) {
        return new JpaRefreshTokenRepository(springDataRefreshTokenRepository);
    }

    @Bean
    SecurityEventRepository securityEventRepository(
        SpringDataSecurityEventRepository springDataSecurityEventRepository
    ) {
        return new JpaSecurityEventRepository(springDataSecurityEventRepository);
    }

    @Bean
    PasswordHasher passwordHasher(
        @Value("${paymesh.security.password.bcrypt-strength}") int bcryptStrength
    ) {
        return new BCryptPasswordHasher(bcryptStrength);
    }

    @Bean
    JwtAccessTokenService accessTokenService(JwtProperties jwtProperties, Clock clock) {
        return new JwtAccessTokenService(
            jwtProperties.secret(),
            jwtProperties.accessTokenTtl(),
            clock
        );
    }

    /**
     * The filter chain verifies tokens with the very decoder that minted them, rather than a second
     * one built from the same property. Two decoders would be two places for the key length check,
     * the accepted algorithm and the clock skew to drift apart -- and a verifier that disagrees
     * with the issuer fails open or closed at the worst possible moment.
     */
    @Bean
    JwtDecoder jwtDecoder(JwtAccessTokenService accessTokenService) {
        return accessTokenService.decoder();
    }

    @Bean
    RegisterUserService registerUserService(
        UserRepository userRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        Clock clock
    ) {
        return new RegisterUserService(
            userRepository,
            securityEventRepository,
            passwordHasher,
            clock
        );
    }

    @Bean
    AuthenticationService authenticationService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        AccessTokenService accessTokenService,
        JwtProperties jwtProperties,
        Clock clock
    ) {
        return new AuthenticationService(
            userRepository,
            refreshTokenRepository,
            securityEventRepository,
            passwordHasher,
            accessTokenService,
            jwtProperties.refreshTokenTtl(),
            clock
        );
    }

    @Bean
    ManageUserAccessService manageUserAccessService(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        org.springframework.transaction.support.TransactionTemplate transactionTemplate,
        Clock clock
    ) {
        return new ManageUserAccessService(
            userRepository, refreshTokenRepository, securityEventRepository, transactionTemplate,
            clock
        );
    }

    /**
     * Promotes the configured email to PLATFORM_ADMIN at startup, if the platform has none.
     *
     * <h2>THE ONLY WAY THE FIRST PLATFORM ADMIN CAN EXIST</h2>
     *
     * {@code POST /api/v1/users/{id}/platform-admin} requires a caller who already holds the role,
     * so on a fresh database it can never mint the first one -- and without one, no merchant can
     * be activated and nothing merchant-scoped works at all. ADR-027.
     *
     * <h2>A RUNNER, NOT AN {@code @EventListener} ON ApplicationReadyEvent</h2>
     *
     * An {@code ApplicationRunner} that throws stops the boot. That is the behaviour worth having:
     * if the bootstrap is misconfigured badly enough to fail, a platform that started anyway would
     * be one nobody can administer, and it would look healthy. A ready-event listener swallows the
     * failure into a log line.
     *
     * <p>Blank property -> the runner does nothing at all, which is the normal case for every boot
     * after the first and for every test. {@code ManageUserAccessService} makes it idempotent
     * regardless, so leaving it set does not re-promote a deliberately demoted admin.
     */
    @Bean
    ApplicationRunner platformAdminBootstrap(
        ManageUserAccessService manageUserAccessService,
        @Value("${paymesh.security.bootstrap-platform-admin-email:}") String bootstrapEmail
    ) {
        return args -> manageUserAccessService.bootstrapPlatformAdmin(bootstrapEmail);
    }
}
