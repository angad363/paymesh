package com.paymesh.shared.infrastructure;

import com.paymesh.identity.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The four ways this application starts, or refuses to, depending on the JWT signing secret.
 * <p>
 * Booting with the committed development secret outside development is a silent authentication
 * bypass: the value is in public git history, so anyone can mint a token for any user at any
 * merchant. Both guards therefore have to stop startup, not log a warning.
 * <p>
 * The last case is the one that earns the other three. A guard that refused every secret would
 * pass the two failure cases and leave the application unable to run anywhere.
 */
class JwtSecretStartupTest {

    private static final String COMMITTED_DEV_SECRET = "dev-only-insecure-jwt-signing-secret-change-me";
    private static final String OPERATOR_SUPPLIED_SECRET = "3zQmA9vK1pR7sT4wY6bC8dF0gH2jL5nP";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(JwtSecretGuards.class);

    /**
     * Reads the real application.yaml and application-dev.yaml, so this fails if the dev profile
     * ever stops carrying a secret -- which would leave a fresh clone unable to run the suite.
     */
    @Test
    void startsWhenTheDevProfileSuppliesTheSecret() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(JwtProperties.class)
                .extracting(JwtProperties::secret)
                .isEqualTo(COMMITTED_DEV_SECRET));
    }

    @Test
    void refusesToStartWhenNoSecretIsConfigured() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("paymesh.security.jwt.secret"));
    }

    @Test
    void refusesToStartWhenTheCommittedDevSecretIsUsedOutsideDevelopment() {
        runner
            .withPropertyValues(
                "paymesh.security.jwt.secret=" + COMMITTED_DEV_SECRET,
                "paymesh.security.jwt.access-token-ttl=15m",
                "paymesh.security.jwt.refresh-token-ttl=30d"
            )
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("PAYMESH_SECURITY_JWT_SECRET"));
    }

    @Test
    void startsWhenAnOperatorSuppliesTheirOwnSecretWithoutTheDevProfile() {
        runner
            .withPropertyValues(
                "paymesh.security.jwt.secret=" + OPERATOR_SUPPLIED_SECRET,
                "paymesh.security.jwt.access-token-ttl=15m",
                "paymesh.security.jwt.refresh-token-ttl=30d"
            )
            .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The two guards as production wires them: IdentityConfiguration enables JwtProperties and
     * component scanning picks up DevelopmentSecretGuard. Importing IdentityConfiguration itself
     * would drag in the JPA repositories it also assembles, which have nothing to do with this.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class JwtSecretGuards {
    }
}
