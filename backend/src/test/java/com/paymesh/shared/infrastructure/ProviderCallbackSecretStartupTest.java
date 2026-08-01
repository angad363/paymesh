package com.paymesh.shared.infrastructure;

import com.paymesh.payment.infrastructure.config.ProviderProperties;
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
 * The same fail-closed rules {@code JwtSecretStartupTest} applies to the signing key, applied to the
 * provider callback secret -- and the stakes here are higher, not lower.
 * <p>
 * This key is the ONLY authentication on {@code POST /internal/v1/provider-callbacks/{provider}},
 * the endpoint that moves a payment to SUCCEEDED. Absent, every callback is unverifiable; set to the
 * value published in this repository, every forged callback verifies and anyone who can reach the
 * port can mark any payment on the platform collected. Both have to stop startup rather than log a
 * warning.
 */
class ProviderCallbackSecretStartupTest {

    private static final String COMMITTED_DEV_SECRET =
        "dev-only-insecure-provider-callback-secret-change-me";
    private static final String OPERATOR_SUPPLIED_SECRET = "8kR2nW5xB7cV1qZ4tY6uM0pL3sJ9hG2d";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ProviderSecretGuards.class);

    /**
     * Reads the real application.yaml and application-dev.yaml, so this fails if the dev profile
     * ever stops carrying the secret -- which would leave a fresh clone unable to run the callback
     * suite at all.
     */
    @Test
    void startsWhenTheDevProfileSuppliesTheSecret() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=dev")
            .run(context -> assertThat(context)
                .hasNotFailed()
                .getBean(ProviderProperties.class)
                .extracting(ProviderProperties::callbackSecret)
                .isEqualTo(COMMITTED_DEV_SECRET));
    }

    @Test
    void refusesToStartWhenNoCallbackSecretIsConfigured() {
        runner
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // Binding reports the bound property name, so it is the relaxed camelCase form rather
            // than the kebab-case spelling in the yaml. Both halves are asserted because either one
            // alone would match some unrelated failure.
            .run(context -> assertThat(context)
                .getFailure()
                .hasStackTraceContaining("paymesh.provider")
                .hasStackTraceContaining("callbackSecret"));
    }

    @Test
    void refusesTheDevSecretWhenDevIsMerelyOneOfSeveralActiveProfiles() {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(
            environmentWith(COMMITTED_DEV_SECRET, "dev", "production")
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_PROVIDER_CALLBACK_SECRET");
    }

    /**
     * Spring does not trim environment variables, and a Kubernetes secret populated from a file or a
     * Docker --env-file routinely carries a trailing newline. A guard that one invisible character
     * defeats is a guard that lets anyone forge a SUCCEEDED callback.
     * <p>
     * These construct the guard directly rather than going through ApplicationContextRunner, whose
     * withPropertyValues trims what it applies: a whitespace case written that way passes against an
     * exact-match comparison and proves nothing.
     */
    @ParameterizedTest
    @ValueSource(strings = {
        COMMITTED_DEV_SECRET,
        COMMITTED_DEV_SECRET + "\n",
        COMMITTED_DEV_SECRET + " ",
        " " + COMMITTED_DEV_SECRET,
        "DEV-ONLY-INSECURE-PROVIDER-CALLBACK-SECRET-CHANGE-ME"
    })
    void refusesTheDevCallbackSecretHoweverItIsSpelled(String secret) {
        assertThatThrownBy(() -> new DevelopmentSecretGuard(environmentWith(secret, "production")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PAYMESH_PROVIDER_CALLBACK_SECRET");
    }

    /** The case that earns the others: a guard refusing every secret would run nowhere. */
    @Test
    void allowsAnOperatorSuppliedCallbackSecret() {
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
            Map.of("paymesh.provider.callback-secret", secret)
        ));

        return environment;
    }

    /**
     * The property binding and the provenance guard, without dragging in the JPA repositories
     * PaymentConfiguration also assembles -- which have nothing to do with this.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ProviderProperties.class)
    @Import(DevelopmentSecretGuard.class)
    static class ProviderSecretGuards {
    }
}
