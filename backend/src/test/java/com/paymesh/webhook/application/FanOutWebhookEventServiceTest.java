package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEventId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Context-free, per the repo's testing convention: the fan-out is plain objects and a Clock. */
class FanOutWebhookEventServiceTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    private final Fakes.FakeEndpoints endpoints = new Fakes.FakeEndpoints();
    private final Fakes.FakeEvents events = new Fakes.FakeEvents();
    private final Fakes.FakeDeliveries deliveries = new Fakes.FakeDeliveries();
    private final AtomicInteger translations = new AtomicInteger();

    private final FanOutWebhookEventService service = new FanOutWebhookEventService(
        endpoints, events, deliveries, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void queuesOneDeliveryPerSubscribedEndpoint() {
        endpoint("https://a.test/hooks", "payment.succeeded");
        endpoint("https://b.test/hooks", "payment.succeeded", "order.paid");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(2);
        assertThat(deliveries.all()).hasSize(2);
        assertThat(events.saved()).hasSize(1);
    }

    @Test
    void ignoresAnEndpointSubscribedToSomethingElse() {
        endpoint("https://a.test/hooks", "refund.succeeded");
        endpoint("https://b.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
    }

    @Test
    void ignoresADisabledEndpoint() {
        endpoints.add(
            endpoint("https://a.test/hooks", "payment.succeeded").disable(NOW)
        );

        assertThat(fanOut("evt_1", "payment.succeeded")).isZero();
    }

    /**
     * NOTHING SUBSCRIBED MEANS NOTHING WRITTEN, and the payload is never even built.
     *
     * <p>The second half is the load-bearing one: translation THROWS on a payload it cannot express,
     * and an event no merchant asked for must not be able to fail and stall the outbox behind it.
     */
    @Test
    void writesNothingAndTranslatesNothingWhenNoEndpointSubscribes() {
        endpoint("https://a.test/hooks", "refund.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isZero();
        assertThat(events.saved()).isEmpty();
        assertThat(deliveries.all()).isEmpty();
        assertThat(translations).hasValue(0);
    }

    /**
     * A REDELIVERED OUTBOX EVENT IS A NO-OP, which is rule 2 of {@code EventHandler}: the inbox stops
     * the same event twice, and this is what stops a second delivery of the same fact.
     */
    @Test
    void aSecondFanOutOfOneSourceEventQueuesNothingNew() {
        endpoint("https://a.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
        assertThat(fanOut("evt_1", "payment.succeeded")).isZero();

        assertThat(events.saved()).hasSize(1);
        assertThat(deliveries.all()).hasSize(1);
    }

    /**
     * THE STORED BYTES WIN. A redelivery reuses the payload written the first time rather than
     * re-translating -- a merchant's signature covers what they received, so a replay that
     * re-serialized would be a different message wearing the same id.
     */
    @Test
    void aRedeliveryReusesTheOriginalPayloadRatherThanRebuildingIt() {
        endpoint("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");
        fanOut("evt_1", "payment.succeeded");

        assertThat(translations).hasValue(1);
    }

    /** An endpoint registered after the first pass still gets the event on a redelivery. */
    @Test
    void anEndpointAddedLaterGetsItsOwnDeliveryOnRedelivery() {
        endpoint("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");

        endpoint("https://b.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
        assertThat(events.saved()).hasSize(1);
        assertThat(deliveries.all()).hasSize(2);
    }

    @Test
    void queuesTheDeliveryPendingAndDueImmediately() {
        endpoint("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");

        WebhookDelivery queued = deliveries.only();

        assertThat(queued.isPending()).isTrue();
        assertThat(queued.nextAttemptAt()).isEqualTo(NOW);
        assertThat(queued.merchantId()).isEqualTo(MERCHANT.value());
    }

    private WebhookEndpoint endpoint(String url, String... subscriptions) {
        return endpoints.add(
            WebhookEndpoint.register(MERCHANT.value(), url, List.of(subscriptions), NOW)
        );
    }

    private int fanOut(String sourceEventId, String eventType) {
        return service.fanOut(
            WebhookEventId.generate(), MERCHANT, sourceEventId, eventType,
            () -> {
                translations.incrementAndGet();

                return "{\"id\":\"whv_x\"}";
            },
            NOW
        );
    }
}
