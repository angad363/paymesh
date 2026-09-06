package com.paymesh.simulator.infrastructure.config;

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
 * The monolith's own {@code DevelopmentSecretGuard} refuses five secrets, one per guarded property,
 * and its own test class (ADR-026's {@code ReconciliationApiKeyStartupTest}) exists because a
 * comment claiming protection is not protection -- a secret is guarded only if a test proves the
 * guard actually throws. This module's one secret gets the identical treatment (ADR-041).
 */
class DevelopmentSecretGuardTest {

    private static final String COMMITTED_DEV_KEY = "dev-only-insecure-simulator-api-key-change-me";
    private static final String OPERATOR_SUPPLIED_KEY = "4pQ7mE1xR9wA6zT3kV8bN2sH5jL0dY7c";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(SimulatorSecretGuards.class);

    /** Reads the real application.yaml and application-dev.yaml, same as the monolith's test does. */
    @Test
    void startsWhenTheDevProfileSuppliesTheKey() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(SimulatorProperties.class)
                .extracting(SimulatorProperties::apiKey)
                .isEqualTo(COMMITTED_DEV_KEY));
    }

    @Test
    void refusesToStartWhenNoApiKeyIsConfigured() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("paymesh.simulator")
                .hasStackTraceContaining("apiKey"));
    }

    /**
     * THE FAILURE THIS WHOLE CLASS IS ABOUT. Without this guard actually throwing, the extracted
     * deployable starts in production on the credential published in this repository.
     */
    @Test
    void refusesTheDevKeyOutsideTheDevProfile() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_SIMULATOR_API_KEY");
    }

    @Test
    void refusesTheDevKeyWhenDevIsMerelyOneOfSeveralActiveProfiles() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "dev", "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_SIMULATOR_API_KEY");
    }

    /**
     * A guard one invisible character defeats is not a guard. Constructed directly rather than
     * through {@code ApplicationContextRunner}, whose {@code withPropertyValues} trims what it
     * applies -- a whitespace case written that way proves nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        COMMITTED_DEV_KEY,
        COMMITTED_DEV_KEY + "\n",
        COMMITTED_DEV_KEY + " ",
        " " + COMMITTED_DEV_KEY,
        "DEV-ONLY-INSECURE-SIMULATOR-API-KEY-CHANGE-ME"
    })
    void refusesTheDevKeyHoweverItIsSpelled(String key) {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(environmentWith(key, "production")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_SIMULATOR_API_KEY");
    }

    /** The case that earns the others: a guard refusing every key would run nowhere. */
    @Test
    void allowsAnOperatorSuppliedApiKey() {
        assertThatCode(() -> new DevelopmentSecretGuard(
            environmentWith(OPERATOR_SUPPLIED_KEY, "production")
        ))
            .doesNotThrowAnyException();
    }

    private static Environment environmentWith(String apiKey, String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        environment.getPropertySources().addFirst(new MapPropertySource(
            "test",
            Map.of("paymesh.simulator.api-key", apiKey)
        ));

        return environment;
    }

    /** The property binding and the provenance guard, without the rest of SimulatorConfiguration. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SimulatorProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class SimulatorSecretGuards {
    }
}
