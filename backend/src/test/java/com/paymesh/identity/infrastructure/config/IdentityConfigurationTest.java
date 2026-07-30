package com.paymesh.identity.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
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
import com.paymesh.identity.infrastructure.security.BCryptPasswordHasher;
import com.paymesh.identity.infrastructure.security.JwtAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the manual wiring actually assembles, including that the identity module
 * reuses the merchant module's Clock bean instead of declaring a competing one --
 * a second Clock would fail context startup, so this test passing is the check.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IdentityConfigurationTest {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityEventRepository securityEventRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenService accessTokenService;
    private final RegisterUserService registerUserService;
    private final AuthenticationService authenticationService;

    @Autowired
    IdentityConfigurationTest(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        AccessTokenService accessTokenService,
        RegisterUserService registerUserService,
        AuthenticationService authenticationService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityEventRepository = securityEventRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenService = accessTokenService;
        this.registerUserService = registerUserService;
        this.authenticationService = authenticationService;
    }

    @Test
    void providesIdentityApplicationBeans() {
        assertNotNull(registerUserService);
        assertNotNull(authenticationService);

        assertInstanceOf(JpaUserRepository.class, userRepository);
        assertInstanceOf(JpaRefreshTokenRepository.class, refreshTokenRepository);
        assertInstanceOf(JpaSecurityEventRepository.class, securityEventRepository);
        assertInstanceOf(BCryptPasswordHasher.class, passwordHasher);
        assertInstanceOf(JwtAccessTokenService.class, accessTokenService);
    }
}
