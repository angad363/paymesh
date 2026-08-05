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

    /** The written schedule from ADR-028 §6, asserted rather than described. */
    @Test
    void reschedulesOnTheWrittenBackoffSchedule() {
        WebhookDelivery delivery = queued();

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(30)));

        delivery = delivery.attemptFailed(503, "unavailable", NOW);
        assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));

        assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.attempts()).isEqualTo(4);
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
