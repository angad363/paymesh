package com.paymesh.shared.infrastructure;

import com.paymesh.reconciliation.infrastructure.config.ReconciliationProperties;
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
 * The same fail-closed rules {@code ProviderCallbackSecretStartupTest} applies, for the fifth
 * guarded secret (ADR-026).
 *
 * <h2>Why this test exists, stated plainly</h2>
 *
 * ADR-026 shipped {@code paymesh.reconciliation.api-key} with a committed development value and a
 * comment in {@code application-dev.yaml} claiming {@code DevelopmentSecretGuard} refused it outside
 * the dev profile. <b>It did not — the entry was missing from the guard.</b> The comment was the
 * only thing asserting the protection, and a comment asserts nothing.
 * <p>
 * The mistake is easy to repeat and unusually easy to make here: this key holds the SAME STRING as
 * {@code paymesh.simulator.api-key}, so an operator copying the value has no visual cue that they
 * are configuring a second credential, and a reviewer comparing the two properties sees identical
 * values and moves on.
 *
 * <h2>Why it is a separate entry rather than an alias of the simulator's</h2>
 *
 * They are the same string for one reason only — the provider is bundled — and they mean opposite
 * things: one is what the provider EXPECTS, this is what the caller SENDS. They stop being the same
 * string the day the provider is external, and guarding them separately makes that split a config
 * change rather than a security regression.
 */
class ReconciliationApiKeyStartupTest {

    private static final String COMMITTED_DEV_KEY = "dev-only-insecure-simulator-api-key-change-me";
    private static final String OPERATOR_SUPPLIED_KEY = "4pQ7mE1xR9wA6zT3kV8bN2sH5jL0dY7c";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ReconciliationSecretGuards.class);

    /**
     * Reads the real application.yaml and application-dev.yaml, so this fails if the dev profile
     * ever stops carrying the key — which would leave a fresh clone unable to reconcile at all.
     */
    @Test
    void startsWhenTheDevProfileSuppliesTheKey() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(ReconciliationProperties.class)
                .extracting(ReconciliationProperties::apiKey)
                .isEqualTo(COMMITTED_DEV_KEY));
    }

    @Test
    void refusesToStartWhenNoApiKeyIsConfigured() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("paymesh.reconciliation")
                .hasStackTraceContaining("apiKey"));
    }

    /**
     * THE FAILURE THIS WHOLE CLASS IS ABOUT. Without the {@code GuardedSecret} entry this passes
     * silently: the application starts in production on a credential published in this repository.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code paymesh.reconciliation.api-key}
     * entry from {@code DevelopmentSecretGuard.GUARDED}.
     */
    @Test
    void refusesTheDevKeyOutsideTheDevProfile() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_RECONCILIATION_API_KEY");
    }

    @Test
    void refusesTheDevKeyWhenDevIsMerelyOneOfSeveralActiveProfiles() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_KEY, "dev", "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_RECONCILIATION_API_KEY");
    }

    /**
     * A guard one invisible character defeats is not a guard. Constructed directly rather than
     * through {@code ApplicationContextRunner}, whose {@code withPropertyValues} trims what it
     * applies — a whitespace case written that way proves nothing.
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
            .hasMessageContaining("PAYMESH_RECONCILIATION_API_KEY");
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
            Map.of("paymesh.reconciliation.api-key", apiKey)
        ));

        return environment;
    }

    /**
     * The property binding and the provenance guard, without the RestClient and the two module
     * adapters {@code ReconciliationConfiguration} also assembles — none of which have anything to
     * do with where a credential came from.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReconciliationProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class ReconciliationSecretGuards {
    }
}
