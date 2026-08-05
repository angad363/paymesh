package com.paymesh.webhook.infrastructure.config;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * THE MASTER KEY IS CHECKED WHEN THE BEAN IS BUILT, NOT ON THE FIRST DELIVERY.
 *
 * <p>{@code WebhookSecrets.requireStrongMasterKey} is documented as existing "so configuration can
 * fail at startup rather than on the first delivery", and for a while nothing called it that way --
 * its only caller was {@code derive} itself, so a key under 32 bytes booted fine and failed on the
 * first merchant to register an endpoint. Review caught it. This pins the fix at the only place it
 * can be pinned without a context: the {@code @Bean} methods themselves.
 *
 * <p>No Spring here on purpose. These are plain method calls on a plain class, which is the whole
 * point of the manual-wiring convention -- a configuration whose methods can only be exercised by
 * booting an application is a configuration nobody tests.
 *
 * <p><b>Sabotage that must turn this red:</b> drop the {@code verifiedMasterKey} /
 * {@code requireStrongMasterKey} calls from {@code WebhookConfiguration}.
 */
class WebhookConfigurationTest {

    private static final String STRONG_KEY = "9tK2wQ7fB4nZ1xM6vC3hJ8sR5dY0pL7a";
    private static final String SHORT_KEY = "9tK2wQ7f";

    private final WebhookConfiguration configuration = new WebhookConfiguration();

    private final WebhookDispatchProperties dispatch = new WebhookDispatchProperties(
        false, 20, Duration.ofSeconds(3), Duration.ofSeconds(5)
    );

    @Test
    void refusesToBuildTheSenderOnAMasterKeyTooShortForTheDerivation() {
        assertThatThrownBy(() -> configuration.webhookSender(
            new WebhookProperties(SHORT_KEY, false), dispatch, new org.springframework.core.env.StandardEnvironment(),
            Clock.systemUTC()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 32 bytes");
    }

    /** The two other beans that hold the key. All three, because one unchecked path is enough. */
    @Test
    void refusesToBuildTheSecretReturningServicesOnAShortMasterKey() {
        WebhookProperties tooShort = new WebhookProperties(SHORT_KEY, false);

        assertThatThrownBy(() -> configuration.registerWebhookEndpointService(
            null, tooShort, null, Clock.systemUTC()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> configuration.rotateWebhookSecretService(
            null, tooShort, null, Clock.systemUTC()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    /** The case that earns the others: a check refusing every key would start nothing. */
    @Test
    void buildsOnAKeyLongEnoughForRfc5869ToPermitSkippingExtract() {
        assertThatCode(() -> configuration.webhookSender(
            new WebhookProperties(STRONG_KEY, false), dispatch, new org.springframework.core.env.StandardEnvironment(),
            Clock.systemUTC()
        )).doesNotThrowAnyException();
    }

    /*
     * THERE IS DELIBERATELY NO TEST HERE FOR THE dev-ONLY SSRF ESCAPE HATCH, and the omission is
     * worth more written down than a green assertion would be.
     *
     * One was drafted: build the sender with allowPrivateAddresses=true under "dev" and under
     * "dev,production", and assert. Assert what? HttpWebhookSender exposes no flag and the guard it
     * wraps is package-private to another package, so the only thing the draft could assert was
     * that neither call threw -- which it would have done whichever way the profile check went. A
     * test that cannot fail is worse than no test, because it reads like coverage.
     *
     * What the rule actually rests on: PrivateAddressGuardTest proves the guard refuses what it
     * should and that the permissive flag disables it, and the profile arithmetic in
     * isDevelopmentOnly is the same two lines DevelopmentSecretGuard uses and its startup tests
     * cover. If you change isDevelopmentOnly here, nothing will fail.
     */
}
