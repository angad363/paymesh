package com.paymesh.identity.infrastructure.config;

import com.paymesh.identity.application.AccessTokenService;
import com.paymesh.identity.application.AuthenticationService;
import com.paymesh.identity.application.PasswordHasher;
import com.paymesh.identity.application.RefreshTokenRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Explicit wiring for the identity module. Application and domain classes carry no
 * Spring annotations; they are plain objects assembled here, which is what keeps
 * them testable without a container.
 *
 * <p>The Clock bean is not declared here: MerchantConfiguration already provides
 * one and a second would be an ambiguous-bean failure at startup.
 */
@Configuration
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
    AccessTokenService accessTokenService(
        @Value("${paymesh.security.jwt.secret}") String jwtSecret,
        @Value("${paymesh.security.jwt.access-token-ttl}") Duration accessTokenTtl,
        Clock clock
    ) {
        return new JwtAccessTokenService(jwtSecret, accessTokenTtl, clock);
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
        @Value("${paymesh.security.jwt.refresh-token-ttl}") Duration refreshTokenTtl,
        Clock clock
    ) {
        return new AuthenticationService(
            userRepository,
            refreshTokenRepository,
            securityEventRepository,
            passwordHasher,
            accessTokenService,
            refreshTokenTtl,
            clock
        );
    }
}
