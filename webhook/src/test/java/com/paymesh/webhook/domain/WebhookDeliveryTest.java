package com.paymesh.webhook.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookDeliveryTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final String MERCHANT = "mrc_550e8400-e29b-41d4-a716-446655440000";

    private static WebhookDelivery queued() {
        return WebhookDelivery.queue(
            WebhookEventId.generate(), EndpointId.generate(), MERCHANT, NOW
        );
    }

    @Test
    void queuesPendingAndDueImmediately() {
        WebhookDelivery delivery = queued();

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isZero();
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void successIsTerminalAndClearsTheSchedule() {
        WebhookDelivery delivered = queued().succeeded(200, "ok", NOW);

        assertThat(delivered.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(delivered.nextAttemptAt()).isNull();
        assertThat(delivered.lastStatusCode()).isEqualTo(200);
        assertThat(delivered.isDead()).isFalse();
    }

    /**
     * THE WHOLE WRITTEN SCHEDULE FROM ADR-028 §6, ASSERTED RATHER THAN DESCRIBED -- and every step
     * of it, which is what this test used not to do.
     *
     * <p>It stopped at the four-hour mark and asserted PENDING, which passed while the six-hour
     * entry was unreachable dead code and {@code MAX_ATTEMPTS} was one too small. A test that walks
     * a list must walk all of it; stopping one short is how the last element goes unexercised.
     */
    @Test
    void reschedulesOnEveryStepOfTheWrittenBackoffSchedule() {
        WebhookDelivery delivery = queued();

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt())
            .as("the six-hour wait, which was unreachable until MAX_ATTEMPTS was corrected")
            .isEqualTo(NOW.plus(Duration.ofHours(6)));

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isEqualTo(5);
    }

    /**
     * THE HORIZON THOSE NUMBERS EXIST TO PRODUCE, asserted as one figure so it cannot drift from
     * the four documents that quote it.
     *
     * <p>1m + 5m + 30m + 2h + 6h = 8h36m: long enough to survive a merchant's overnight deploy in a
     * timezone where nobody is awake, and short of a day so a dead endpoint does not hold rows
     * indefinitely. It was 2h36m before {@code MAX_ATTEMPTS} was corrected.
     */
    @Test
    void spansTheRetryHorizonAdr028Claims() {
        WebhookDelivery delivery = queued();
        Instant clock = NOW;

        for (int attempt = 1; attempt < WebhookDelivery.MAX_ATTEMPTS; attempt++) {
            delivery = delivery.attemptFailed(503, "unavailable", clock);
            clock = delivery.nextAttemptAt();
        }

        assertThat(Duration.between(NOW, clock))
            .isEqualTo(Duration.ofHours(8).plusMinutes(36));

        assertThat(delivery.attemptFailed(503, "unavailable", clock).status())
            .as("the next failure after the last wait is the one that gives up")
            .isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    void diesOnceTheBudgetIsSpent() {
        WebhookDelivery delivery = queued();

        for (int i = 0; i < WebhookDelivery.MAX_ATTEMPTS; i++) {
            delivery = delivery.attemptFailed(500, "boom", NOW);
        }

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.isDead()).isTrue();
        assertThat(delivery.nextAttemptAt())
            .as("a terminal delivery must not stay scheduled, per ck_webhook_deliveries_schedule")
            .isNull();
    }

    /** A refused connection has no status code, and the nullable column is why. */
    @Test
    void recordsAFailureThatNeverGotAStatusCode() {
        WebhookDelivery delivery = queued().attemptFailed(null, "Connection refused", NOW);

        assertThat(delivery.lastStatusCode()).isNull();
        assertThat(delivery.lastResponseExcerpt()).isEqualTo("Connection refused");
    }

    @Test
    void capsTheStoredResponseExcerpt() {
        String huge = "x".repeat(5_000);

        WebhookDelivery delivery = queued().attemptFailed(500, huge, NOW);

        assertThat(delivery.lastResponseExcerpt())
            .hasSize(WebhookDelivery.MAX_RESPONSE_EXCERPT);
    }

    @Test
    void replayResetsTheBudgetAndQueuesItAgain() {
        WebhookDelivery delivery = queued();

        for (int i = 0; i < WebhookDelivery.MAX_ATTEMPTS; i++) {
            delivery = delivery.attemptFailed(500, "boom", NOW);
        }

        WebhookDelivery replayed = delivery.replay(NOW.plusSeconds(60));

        assertThat(replayed.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replayed.attempts()).isZero();
        assertThat(replayed.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
    }

    /**
     * REPLAYING A PENDING DELIVERY WOULD SEND IT TWICE -- once for the replay and once for the
     * retry the schedule already had queued.
     */
    @Test
    void refusesToReplayADeliveryTheDispatcherWillRetryAnyway() {
        WebhookDelivery delivery = queued().attemptFailed(503, "later", NOW);

        assertThatThrownBy(() -> delivery.replay(NOW))
            .isInstanceOf(DeliveryNotReplayableException.class);
    }

    @Test
    void replaysADeliveredOneToo() {
        WebhookDelivery delivered = queued().succeeded(200, "ok", NOW);

        assertThat(delivered.replay(NOW).status()).isEqualTo(DeliveryStatus.PENDING);
    }
}
