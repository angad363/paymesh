package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.DeliveryStatus;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeliverWebhooksServiceTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    private static final String PAYLOAD = "{\"id\":\"whv_x\",\"type\":\"payment.succeeded\"}";

    private final Fakes.FakeEndpoints endpoints = new Fakes.FakeEndpoints();
    private final Fakes.FakeEvents events = new Fakes.FakeEvents();
    private final Fakes.FakeDeliveries deliveries = new Fakes.FakeDeliveries();
    private final Fakes.ScriptedSender sender = new Fakes.ScriptedSender();
    private final Fakes.ImmediateTransactions transactions = new Fakes.ImmediateTransactions();

    private final DeliverWebhooksService service = new DeliverWebhooksService(
        deliveries, endpoints, events, sender, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC), 20
    );

    @Test
    void deliversADueDeliveryAndSendsTheStoredBytes() {
        queue(endpoint());

        DeliverWebhooksService.DispatchResult result = service.dispatch();

        assertThat(result.examined()).isEqualTo(1);
        assertThat(result.delivered()).isEqualTo(1);
        assertThat(sender.sent()).containsExactly(PAYLOAD);
        assertThat(deliveries.only().status()).isEqualTo(DeliveryStatus.DELIVERED);
    }

    @Test
    void runsOneTransactionPerDelivery() {
        queue(endpoint("https://a.test/hooks"));
        queue(endpoint("https://b.test/hooks"));

        service.dispatch();

        assertThat(transactions.executions()).isEqualTo(2);
    }

    @Test
    void reschedulesOnANonSuccessStatus() {
        queue(endpoint());
        sender.answer(WebhookSender.WebhookSendResult.refused(503, "unavailable"));

        DeliverWebhooksService.DispatchResult result = service.dispatch();

        assertThat(result.retried()).isEqualTo(1);
        assertThat(deliveries.only().status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(deliveries.only().attempts()).isEqualTo(1);
        assertThat(deliveries.only().nextAttemptAt()).isAfter(NOW);
    }

    /**
     * ONE DEAD DELIVERY MOVES THE ENDPOINT'S STREAK BY ONE, not by the five attempts it spent.
     * Confusing the two is a factor of five in when an endpoint disables. ADR-028 §6.
     */
    @Test
    void aDeliveryThatSpendsItsWholeBudgetMovesTheEndpointStreakByExactlyOne() {
        WebhookEndpoint endpoint = endpoint();

        deliveries.save(exhausted(endpoint));
        sender.answer(WebhookSender.WebhookSendResult.refused(500, "boom"));

        DeliverWebhooksService.DispatchResult result = service.dispatch();

        assertThat(result.dead()).isEqualTo(1);
        assertThat(deliveries.only().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(current(endpoint).consecutiveFailures()).isEqualTo(1);
        assertThat(current(endpoint).isActive()).isTrue();
    }

    @Test
    void disablesTheEndpointAtTheThreshold() {
        WebhookEndpoint endpoint = endpoint();

        for (int i = 0; i < WebhookEndpoint.DISABLE_AFTER_CONSECUTIVE_FAILURES - 1; i++) {
            endpoint = endpoints.add(current(endpoint).recordDeadDelivery(NOW));
        }

        deliveries.save(exhausted(endpoint));
        sender.answer(WebhookSender.WebhookSendResult.refused(500, "boom"));

        service.dispatch();

        assertThat(current(endpoint).isActive()).isFalse();
        assertThat(current(endpoint).consecutiveFailures())
            .isEqualTo(WebhookEndpoint.DISABLE_AFTER_CONSECUTIVE_FAILURES);
    }

    /** Any success clears the streak, so an endpoint that works intermittently is never disabled. */
    @Test
    void aSuccessClearsTheStreak() {
        WebhookEndpoint endpoint = endpoints.add(endpoint().recordDeadDelivery(NOW));

        queue(endpoint);

        service.dispatch();

        assertThat(current(endpoint).consecutiveFailures()).isZero();
    }

    /**
     * A DISABLED ENDPOINT IS NOT DIALLED, and its backlog does not sit PENDING forever either: the
     * attempt is recorded as a failure, so the queue drains to FAILED on the ordinary schedule.
     */
    @Test
    void doesNotSendToADisabledEndpointButStillDrainsItsBacklog() {
        WebhookEndpoint endpoint = endpoints.add(endpoint().disable(NOW));

        queue(endpoint);

        DeliverWebhooksService.DispatchResult result = service.dispatch();

        assertThat(sender.sent()).isEmpty();
        assertThat(result.retried()).isEqualTo(1);
        assertThat(deliveries.only().attempts()).isEqualTo(1);
        assertThat(deliveries.only().lastResponseExcerpt()).isEqualTo("Endpoint is disabled");
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        assertThat(service.dispatch().examined()).isZero();
        assertThat(sender.sent()).isEmpty();
    }

    /**
     * A terminal row is not a candidate, which is what {@code idx_webhook_deliveries_due} being
     * partial on {@code status = 'PENDING'} says in the database.
     */
    @Test
    void doesNotConsiderATerminalDeliveryDue() {
        WebhookDelivery queued = queue(endpoint());

        deliveries.save(queued.succeeded(200, "ok", NOW));

        assertThat(service.dispatch().examined()).isZero();
        assertThat(sender.sent()).isEmpty();
    }

    /** A retry scheduled into the future is not due yet. */
    @Test
    void doesNotSendADeliveryWhoseBackoffHasNotElapsed() {
        WebhookDelivery queued = queue(endpoint());

        deliveries.save(queued.attemptFailed(503, "later", NOW));

        assertThat(service.dispatch().examined()).isZero();
    }

    private WebhookEndpoint endpoint() {
        return endpoint("https://merchant.test/hooks");
    }

    private WebhookEndpoint endpoint(String url) {
        return endpoints.add(WebhookEndpoint.register(
            MERCHANT.value(), url, List.of("payment.succeeded"), NOW
        ));
    }

    /** The stored aggregate, which the service replaces as it records outcomes. */
    private WebhookEndpoint current(WebhookEndpoint endpoint) {
        return endpoints.findByEndpointId(MERCHANT, endpoint.endpointId()).orElseThrow();
    }

    private WebhookDelivery queue(WebhookEndpoint endpoint) {
        WebhookEventId eventId = WebhookEventId.generate();

        events.save(WebhookEvent.translate(
            eventId, MERCHANT.value(), "evt_" + eventId.value(), "payment.succeeded",
            PAYLOAD, NOW, NOW
        ));

        return deliveries.save(
            WebhookDelivery.queue(eventId, endpoint.endpointId(), MERCHANT.value(), NOW)
        );
    }

    /**
     * One attempt short of dead, so the next failure is the one that spends the budget.
     * <p>
     * Aged backwards on purpose: the fourth failure schedules two hours out, so a delivery failed
     * "now" four times would not be due now. Three hours ago it is.
     */
    private WebhookDelivery exhausted(WebhookEndpoint endpoint) {
        WebhookDelivery delivery = queue(endpoint);
        Instant earlier = NOW.minus(Duration.ofHours(3));

        for (int i = 0; i < WebhookDelivery.MAX_ATTEMPTS - 1; i++) {
            delivery = delivery.attemptFailed(500, "boom", earlier);
        }

        return delivery;
    }
}
