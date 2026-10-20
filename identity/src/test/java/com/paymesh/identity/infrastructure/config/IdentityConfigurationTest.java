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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Proves the manual wiring actually assembles, including that every application service here
 * resolves to the single {@code Clock} bean {@code SharedBeansConfiguration} declares (ADR-045) --
 * a second, competing Clock bean would fail context startup, so this test passing is the check.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class IdentityConfigurationTest {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityEventRepository securityEventRepository;
    private final PasswordHasher passwordHasher;
    private final AccessTokenService accessTokenService;
    private final RegisterUserService registerUserService;
    private final AuthenticationService authenticationService;
    private final ApplicationContext context;

    @Autowired
    IdentityConfigurationTest(
        UserRepository userRepository,
        RefreshTokenRepository refreshTokenRepository,
        SecurityEventRepository securityEventRepository,
        PasswordHasher passwordHasher,
        AccessTokenService accessTokenService,
        RegisterUserService registerUserService,
        AuthenticationService authenticationService,
        ApplicationContext context
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityEventRepository = securityEventRepository;
        this.passwordHasher = passwordHasher;
        this.accessTokenService = accessTokenService;
        this.registerUserService = registerUserService;
        this.authenticationService = authenticationService;
        this.context = context;
    }

    /**
     * The outbox relay's {@code @Scheduled} method only fires if scheduling is enabled, and this
     * deployable has no monolith configuration to enable it for it. The post-processor below is
     * registered by {@code @EnableScheduling} and by nothing else, so its presence is the check;
     * remove the annotation from {@code IdentityConfiguration} and this fails.
     */
    @Test
    void schedulingIsEnabledSoTheOutboxRelayRuns() {
        assertFalse(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
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
