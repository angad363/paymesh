package com.paymesh.shared.infrastructure;

import com.paymesh.identity.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
     * "The dev profile is active" has to mean dev and nothing else. {@code dev,production} is a
     * layered-configuration accident waiting to happen -- a Helm overlay or a compose env_file
     * appends a profile rather than replacing one -- and it produces the worst instance of all:
     * real datasource credentials from the environment, tokens signed with the published key.
     */
    @Test
    void refusesTheDevSecretWhenDevIsMerelyOneOfSeveralActiveProfiles() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_SECRET, "dev", "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_SECURITY_JWT_SECRET");
    }

    @Test
    void allowsTheDevSecretWhenDevIsTheOnlyActiveProfile() {
        assertThatCode(() -> new DevelopmentSecretGuard(environmentWith(COMMITTED_DEV_SECRET, "dev")))
            .doesNotThrowAnyException();
    }

    /**
     * Spring does not trim environment variables. A Kubernetes secret populated from a file, or a
     * Docker --env-file, routinely carries a trailing newline, and a guard that a single invisible
     * character defeats is a guard that signs real tokens with a public string.
     * <p>
     * These construct the guard directly instead of going through ApplicationContextRunner, whose
     * withPropertyValues trims what it applies: a whitespace case written that way passes against
     * an exact-match comparison and proves nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        COMMITTED_DEV_SECRET,
        COMMITTED_DEV_SECRET + "\n",
        COMMITTED_DEV_SECRET + " ",
        " " + COMMITTED_DEV_SECRET,
        "DEV-ONLY-INSECURE-JWT-SIGNING-SECRET-CHANGE-ME"
    })
    void refusesTheDevSecretHoweverItIsSpelled(String secret) {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(environmentWith(secret, "production")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_SECURITY_JWT_SECRET");
    }

    @Test
    void allowsAnOperatorSuppliedSecretThatMerelyResemblesNothingCommitted() {
        assertThatCode(() -> new DevelopmentSecretGuard(
            environmentWith(OPERATOR_SUPPLIED_SECRET, "production")
        ))
            .doesNotThrowAnyException();
    }

    private static Environment environmentWith(String secret, String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        environment.getPropertySources().addFirst(new MapPropertySource(
            "test",
            Map.of("paymesh.security.jwt.secret", secret)
        ));

        return environment;
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
