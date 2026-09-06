package com.paymesh.webhook.infrastructure.config;

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
 * The monolith's own {@code DevelopmentSecretGuard} used to refuse this key before this capability
 * was extracted (ADR-042); this is that same test class, moved with the guard rather than left
 * behind as a comment claiming protection nobody proves. A comment is not protection -- a secret is
 * guarded only if a test proves the guard actually throws (the same reasoning
 * {@code provider-sim}'s own copy of this test states for its one secret, ADR-041).
 */
class DevelopmentSecretGuardTest {

    private static final String COMMITTED_DEV_KEY = "dev-only-insecure-webhook-master-key-change-me";
    private static final String OPERATOR_SUPPLIED_KEY = "9tK2wQ7fB4nZ1xM6vC3hJ8sR5dY0pL7a";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WebhookSecretGuards.class);

    /** Reads the real application.yaml and application-dev.yaml, same as the monolith's test did. */
    @Test
    void startsWhenTheDevProfileSuppliesTheKey() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(WebhookProperties.class)
                .extracting(WebhookProperties::masterKey)
                .isEqualTo(COMMITTED_DEV_KEY));
    }

    @Test
    void refusesToStartWhenNoMasterKeyIsConfigured() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("paymesh.webhook")
                .hasStackTraceContaining("masterKey"));
    }

    /**
     * THE FAILURE THIS WHOLE CLASS IS ABOUT. Without this guard actually throwing, this deployable
     * starts in production signing every merchant's webhooks with a key published in this
     * repository, and every merchant's verifier accepts anything a reader of this repository
     * chooses to send them.
     */
    @Test
    void refusesTheDevKeyOutsideTheDevProfile() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_WEBHOOK_MASTER_KEY");
    }

    @Test
    void refusesTheDevKeyWhenDevIsMerelyOneOfSeveralActiveProfiles() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "dev", "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_WEBHOOK_MASTER_KEY");
    }

    /** A guard one invisible character defeats is not a guard. */
    @ParameterizedTest
    @ValueSource(strings = {
        COMMITTED_DEV_KEY,
        COMMITTED_DEV_KEY + "\n",
        COMMITTED_DEV_KEY + " ",
        " " + COMMITTED_DEV_KEY,
        "DEV-ONLY-INSECURE-WEBHOOK-MASTER-KEY-CHANGE-ME"
    })
    void refusesTheDevKeyHoweverItIsSpelled(String key) {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(environmentWith(key, "production")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_WEBHOOK_MASTER_KEY");
    }

    /** The case that earns the others: a guard refusing every key would run nowhere. */
    @Test
    void allowsAnOperatorSuppliedMasterKey() {
        assertThatCode(() -> new DevelopmentSecretGuard(
            environmentWith(OPERATOR_SUPPLIED_KEY, "production")
        ))
            .doesNotThrowAnyException();
    }

    private static Environment environmentWith(String masterKey, String... activeProfiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles(activeProfiles);
        environment.getPropertySources().addFirst(new MapPropertySource(
            "test",
            Map.of("paymesh.webhook.master-key", masterKey)
        ));

        return environment;
    }

    /** The property binding and the provenance guard, without the rest of WebhookConfiguration. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class WebhookSecretGuards {
    }
}
