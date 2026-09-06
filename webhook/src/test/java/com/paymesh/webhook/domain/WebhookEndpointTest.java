package com.paymesh.webhook.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookEndpointTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final String MERCHANT = "mrc_550e8400-e29b-41d4-a716-446655440000";

    private static WebhookEndpoint endpoint() {
        return WebhookEndpoint.register(
            MERCHANT, "https://merchant.test/hooks", List.of("payment.succeeded"), NOW
        );
    }

    @Test
    void registersActiveAtVersionOneWithNoRotationWindow() {
        WebhookEndpoint endpoint = endpoint();

        assertThat(endpoint.status()).isEqualTo(EndpointStatus.ACTIVE);
        assertThat(endpoint.secretVersion()).isEqualTo(1);
        assertThat(endpoint.previousSecretVersion()).isNull();
        assertThat(endpoint.consecutiveFailures()).isZero();
        assertThat(endpoint.signingVersions(NOW)).containsExactly(1);
    }

    // --- the URL rules, which are half the SSRF story ------------------------------------------

    @Test
    void refusesAPlaintextHttpUrl() {
        assertThatThrownBy(() -> WebhookEndpoint.register(
            MERCHANT, "http://merchant.test/hooks", List.of("payment.succeeded"), NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("https");
    }

    /**
     * THE ONE THE DATABASE CHECK CANNOT CATCH. {@code ck_webhook_endpoints_url_https} is a regex on
     * the scheme; credentials sit after it and pass any prefix test.
     */
    @Test
    void refusesAUrlCarryingCredentialsInItsUserinfo() {
        assertThatThrownBy(() -> WebhookEndpoint.register(
            MERCHANT, "https://user:pass@internal.test/hooks", List.of("payment.succeeded"), NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("userinfo");
    }

    /** Accepted here on purpose: the address check belongs at send time, not registration. */
    @Test
    void acceptsAUrlWhoseHostIsOnlyRejectableOnceResolved() {
        WebhookEndpoint endpoint = WebhookEndpoint.register(
            MERCHANT, "https://internal.test/hooks", List.of("payment.succeeded"), NOW
        );

        assertThat(endpoint.url()).isEqualTo("https://internal.test/hooks");
    }

    @Test
    void refusesAUrlWithNoHost() {
        assertThatThrownBy(() -> WebhookEndpoint.register(
            MERCHANT, "https:///hooks", List.of("payment.succeeded"), NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("host");
    }

    @Test
    void refusesAnEndpointSubscribedToNothing() {
        assertThatThrownBy(() -> WebhookEndpoint.register(
            MERCHANT, "https://merchant.test/hooks", List.of(), NOW
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least one");
    }

    @Test
    void collapsesDuplicateSubscriptions() {
        WebhookEndpoint endpoint = WebhookEndpoint.register(
            MERCHANT, "https://merchant.test/hooks",
            List.of("payment.succeeded", "payment.succeeded"), NOW
        );

        assertThat(endpoint.subscriptions()).containsExactly("payment.succeeded");
    }

    // --- rotation -------------------------------------------------------------------------------

    @Test
    void rotationBumpsTheVersionAndKeepsThePreviousOneSigning() {
        WebhookEndpoint rotated = endpoint().rotateSecret(1, NOW);

        assertThat(rotated.secretVersion()).isEqualTo(2);
        assertThat(rotated.previousSecretVersion()).isEqualTo(1);
        assertThat(rotated.signingVersions(NOW)).containsExactly(2, 1);
    }

    /**
     * WITHOUT THE WINDOW, ROTATION IS AN OUTAGE. A merchant who rotates and has not yet shipped
     * their new verifier would fail every signature check until they did.
     */
    @Test
    void thePreviousVersionStopsSigningOnceTheWindowExpires() {
        WebhookEndpoint rotated = endpoint().rotateSecret(1, NOW);
        Instant afterWindow = NOW.plus(WebhookEndpoint.ROTATION_OVERLAP).plusSeconds(1);

        assertThat(rotated.signingVersions(afterWindow)).containsExactly(2);
    }

    @Test
    void thePreviousVersionStillSignsOnTheLastSecondOfTheWindow() {
        WebhookEndpoint rotated = endpoint().rotateSecret(1, NOW);
        Instant justInside = NOW.plus(WebhookEndpoint.ROTATION_OVERLAP).minusSeconds(1);

        assertThat(rotated.signingVersions(justInside)).containsExactly(2, 1);
    }

    /**
     * THE PROPERTY THAT KEEPS THE SECRET OUT OF THE DATABASE. Because a retried rotate re-derives
     * rather than bumping again, this route needs no IdempotencyFilter -- and the filter persists
     * response bodies verbatim, so registering it would store the secret in cleartext.
     */
    @Test
    void rotatingAgainFromTheAlreadyReplacedVersionChangesNothing() {
        WebhookEndpoint rotated = endpoint().rotateSecret(1, NOW);
        WebhookEndpoint retried = rotated.rotateSecret(1, NOW.plusSeconds(5));

        assertThat(retried.secretVersion()).isEqualTo(2);
        assertThat(retried).isSameAs(rotated);
    }

    @Test
    void refusesRotationFromAVersionThatWasNeverCurrent() {
        assertThatThrownBy(() -> endpoint().rotateSecret(7, NOW))
            .isInstanceOf(SecretVersionMismatchException.class);
    }

    // --- the failure counter --------------------------------------------------------------------

    /** One dead delivery is one, not its five spent attempts. A factor of five, pinned. */
    @Test
    void oneDeadDeliveryIncrementsTheStreakByExactlyOne() {
        WebhookEndpoint endpoint = endpoint().recordDeadDelivery(NOW);

        assertThat(endpoint.consecutiveFailures()).isEqualTo(1);
        assertThat(endpoint.status()).isEqualTo(EndpointStatus.ACTIVE);
    }

    @Test
    void disablesItselfAtTheThreshold() {
        WebhookEndpoint endpoint = endpoint();

        for (int i = 0; i < WebhookEndpoint.DISABLE_AFTER_CONSECUTIVE_FAILURES; i++) {
            endpoint = endpoint.recordDeadDelivery(NOW.plus(Duration.ofMinutes(i)));
        }

        assertThat(endpoint.status()).isEqualTo(EndpointStatus.DISABLED);
    }

    @Test
    void staysActiveOneShortOfTheThreshold() {
        WebhookEndpoint endpoint = endpoint();

        for (int i = 0; i < WebhookEndpoint.DISABLE_AFTER_CONSECUTIVE_FAILURES - 1; i++) {
            endpoint = endpoint.recordDeadDelivery(NOW.plus(Duration.ofMinutes(i)));
        }

        assertThat(endpoint.status()).isEqualTo(EndpointStatus.ACTIVE);
    }

    /** An endpoint that works intermittently must never be disabled. */
    @Test
    void anySuccessClearsTheStreak() {
        WebhookEndpoint endpoint = endpoint()
            .recordDeadDelivery(NOW)
            .recordDeadDelivery(NOW)
            .recordSuccess(NOW);

        assertThat(endpoint.consecutiveFailures()).isZero();
    }

    @Test
    void reEnablingClearsTheStreakSoARecoveredEndpointStartsFromZero() {
        WebhookEndpoint endpoint = endpoint().recordDeadDelivery(NOW).disable(NOW).enable(NOW);

        assertThat(endpoint.status()).isEqualTo(EndpointStatus.ACTIVE);
        assertThat(endpoint.consecutiveFailures()).isZero();
    }

    @Test
    void reportsWhatItIsSubscribedTo() {
        WebhookEndpoint endpoint = endpoint();

        assertThat(endpoint.isSubscribedTo("payment.succeeded")).isTrue();
        assertThat(endpoint.isSubscribedTo("refund.succeeded")).isFalse();
    }
}
