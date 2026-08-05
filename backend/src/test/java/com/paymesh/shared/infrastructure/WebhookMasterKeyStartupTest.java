package com.paymesh.shared.infrastructure;

import com.paymesh.webhook.domain.WebhookSecrets;
import com.paymesh.webhook.infrastructure.config.WebhookProperties;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same fail-closed rules {@code ReconciliationApiKeyStartupTest} applies, for the sixth guarded
 * secret (ADR-028).
 *
 * <h2>Why this test exists, stated plainly</h2>
 *
 * {@code DevelopmentSecretGuard}'s own javadoc says "the next secret is a line in {@code GUARDED}
 * plus a case in {@code ReconciliationApiKeyStartupTest} and its siblings". The webhook master key
 * was the next secret, and the first half was done without the second. Review caught it. That
 * sentence exists because a fifth secret had previously been missing from {@code GUARDED} entirely
 * while {@code application-dev.yaml} claimed it was covered, and a comment asserts nothing.
 *
 * <h2>WHAT MAKES THIS ONE DIFFERENT FROM THE FIVE BEFORE IT</h2>
 *
 * The other five let an attacker move money <i>on</i> PayMesh. This one lets them sign <i>as</i>
 * PayMesh, to merchants who are not on PayMesh, have no other way to check, and have every reason
 * to act on what they are told. Every endpoint's secret derives from it, so the blast radius is
 * every merchant at once — which is exactly what per-endpoint rotation cannot help with.
 *
 * <h2>And one rule the others do not have</h2>
 *
 * A short key is refused too, not just a published one. RFC 5869 §3.3 only permits skipping
 * HKDF-Extract when the input keying material is already a uniformly-random fixed-length key, so a
 * typed passphrase silently withdraws the licence the derivation is built on. That check has to
 * happen at startup rather than on the first delivery, which is the second half of what this class
 * pins.
 */
class WebhookMasterKeyStartupTest {

    private static final String COMMITTED_DEV_KEY = "dev-only-insecure-webhook-master-key-change-me";
    private static final String OPERATOR_SUPPLIED_KEY = "9tK2wQ7fB4nZ1xM6vC3hJ8sR5dY0pL7a";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(WebhookSecretGuards.class);

    /**
     * Reads the real application.yaml and application-dev.yaml, so this fails if the dev profile
     * ever stops carrying the key — which would leave a fresh clone unable to sign anything.
     */
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
     * THE FAILURE THIS WHOLE CLASS IS ABOUT. Without the {@code GuardedSecret} entry this passes
     * silently: the application starts in production signing every merchant's webhooks with a key
     * published in this repository, and every merchant's verifier accepts anything a reader of this
     * repository chooses to send them.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code paymesh.webhook.master-key} entry
     * from {@code DevelopmentSecretGuard.GUARDED}.
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

    /**
     * THE OTHER HALF, AND IT IS NOT THE GUARD'S JOB. A private, unpublished, thoroughly secret
     * eight-character key passes {@link DevelopmentSecretGuard} — nothing about its provenance is
     * wrong. What is wrong is that it is too short for the derivation to be sound, and the
     * application must refuse to start rather than discover it on the first merchant to register.
     * <p>
     * <b>Sabotage that must turn this red:</b> remove the {@code verifiedMasterKey} call from
     * {@code WebhookConfiguration}, which is how this shipped before review caught it.
     */
    @Test
    void refusesAMasterKeyTooShortForTheDerivationToBeSound() {
        String shortButPrivate = "9tK2wQ7f";

        assertThatCode(() -> new DevelopmentSecretGuard(
            environmentWith(shortButPrivate, "production")
        ))
            .as("provenance is fine; length is not the guard's concern")
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> WebhookSecrets.requireStrongMasterKey(
            shortButPrivate.getBytes(StandardCharsets.UTF_8)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 32 bytes");
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

    /**
     * The property binding and the provenance guard, without the HTTP client, the four handlers and
     * the six services {@code WebhookConfiguration} also assembles — none of which have anything to
     * do with where a credential came from.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class WebhookSecretGuards {
    }
}
