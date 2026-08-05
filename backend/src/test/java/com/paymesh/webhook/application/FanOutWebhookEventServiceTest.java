package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Context-free, per the repo's testing convention: the fan-out is plain objects and a Clock. */
class FanOutWebhookEventServiceTest {

    private static final MerchantId MERCHANT =
        MerchantId.from("mrc_550e8400-e29b-41d4-a716-446655440000");

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    private final FakeEndpoints endpoints = new FakeEndpoints();
    private final FakeEvents events = new FakeEvents();
    private final FakeDeliveries deliveries = new FakeDeliveries();
    private final AtomicInteger translations = new AtomicInteger();

    private final FanOutWebhookEventService service = new FanOutWebhookEventService(
        endpoints, events, deliveries, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void queuesOneDeliveryPerSubscribedEndpoint() {
        endpoints.add("https://a.test/hooks", "payment.succeeded");
        endpoints.add("https://b.test/hooks", "payment.succeeded", "order.paid");

        int queued = fanOut("evt_1", "payment.succeeded");

        assertThat(queued).isEqualTo(2);
        assertThat(deliveries.saved).hasSize(2);
        assertThat(events.saved).hasSize(1);
    }

    @Test
    void ignoresAnEndpointSubscribedToSomethingElse() {
        endpoints.add("https://a.test/hooks", "refund.succeeded");
        endpoints.add("https://b.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
    }

    /**
     * NOTHING SUBSCRIBED MEANS NOTHING WRITTEN, and the payload is never even built.
     *
     * <p>The second half is the load-bearing one: translation throws on a payload it cannot express,
     * and an event no merchant asked for must not be able to fail and stall the outbox behind it.
     */
    @Test
    void writesNothingAndTranslatesNothingWhenNoEndpointSubscribes() {
        endpoints.add("https://a.test/hooks", "refund.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isZero();
        assertThat(events.saved).isEmpty();
        assertThat(deliveries.saved).isEmpty();
        assertThat(translations).hasValue(0);
    }

    /**
     * A REDELIVERED OUTBOX EVENT IS A NO-OP, which is rule 2 of {@code EventHandler}: the inbox stops
     * the same event twice, and this is what stops a different delivery of the same fact.
     */
    @Test
    void aSecondFanOutOfOneSourceEventQueuesNothingNew() {
        endpoints.add("https://a.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
        assertThat(fanOut("evt_1", "payment.succeeded")).isZero();

        assertThat(events.saved).hasSize(1);
        assertThat(deliveries.saved).hasSize(1);
    }

    /**
     * THE STORED BYTES WIN. A redelivery reuses the payload written the first time rather than
     * re-translating -- a merchant's signature covers what they received, so a replay that
     * re-serialized would be a different message wearing the same id.
     */
    @Test
    void aRedeliveryReusesTheOriginalPayloadRatherThanRebuildingIt() {
        endpoints.add("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");

        int translationsAfterFirst = translations.get();

        fanOut("evt_1", "payment.succeeded");

        assertThat(translations).hasValue(translationsAfterFirst);
    }

    /** An endpoint registered after the first pass still gets the event on a redelivery. */
    @Test
    void anEndpointAddedLaterGetsItsOwnDeliveryOnRedelivery() {
        endpoints.add("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");

        endpoints.add("https://b.test/hooks", "payment.succeeded");

        assertThat(fanOut("evt_1", "payment.succeeded")).isEqualTo(1);
        assertThat(events.saved).hasSize(1);
        assertThat(deliveries.saved).hasSize(2);
    }

    @Test
    void queuesTheDeliveryPendingAndDueImmediately() {
        endpoints.add("https://a.test/hooks", "payment.succeeded");

        fanOut("evt_1", "payment.succeeded");

        WebhookDelivery queued = deliveries.saved.getFirst();

        assertThat(queued.isPending()).isTrue();
        assertThat(queued.nextAttemptAt()).isEqualTo(NOW);
        assertThat(queued.merchantId()).isEqualTo(MERCHANT.value());
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

    private static final class FakeEndpoints implements WebhookEndpointRepository {

        private final List<WebhookEndpoint> stored = new ArrayList<>();

        void add(String url, String... subscriptions) {
            stored.add(WebhookEndpoint.register(MERCHANT.value(), url, List.of(subscriptions), NOW));
        }

        @Override
        public WebhookEndpoint save(WebhookEndpoint endpoint) {
            stored.add(endpoint);
            return endpoint;
        }

        @Override
        public Optional<WebhookEndpoint> findByEndpointId(MerchantId merchantId, EndpointId id) {
            return stored.stream().filter(e -> e.endpointId().equals(id)).findFirst();
        }

        @Override
        public List<WebhookEndpoint> findByMerchant(MerchantId merchantId) {
            return List.copyOf(stored);
        }

        @Override
        public List<WebhookEndpoint> findActiveByMerchant(MerchantId merchantId) {
            return stored.stream().filter(WebhookEndpoint::isActive).toList();
        }

        @Override
        public long countByMerchant(MerchantId merchantId) {
            return stored.size();
        }
    }

    private static final class FakeEvents implements WebhookEventRepository {

        private final List<WebhookEvent> saved = new ArrayList<>();

        @Override
        public WebhookEvent save(WebhookEvent event) {
            saved.add(event);
            return event;
        }

        @Override
        public Optional<WebhookEvent> findBySourceEventId(String sourceEventId) {
            return saved.stream().filter(e -> e.sourceEventId().equals(sourceEventId)).findFirst();
        }

        @Override
        public Optional<WebhookEvent> findById(WebhookEventId webhookEventId) {
            return saved.stream().filter(e -> e.webhookEventId().equals(webhookEventId)).findFirst();
        }
    }

    /** Keyed the way {@code uq_webhook_deliveries_event_endpoint} is, which is the point of it. */
    private static final class FakeDeliveries implements WebhookDeliveryRepository {

        private final List<WebhookDelivery> saved = new ArrayList<>();
        private final Map<String, WebhookDelivery> byEventAndEndpoint = new LinkedHashMap<>();

        @Override
        public WebhookDelivery save(WebhookDelivery delivery) {
            saved.add(delivery);
            byEventAndEndpoint.put(key(delivery), delivery);
            return delivery;
        }

        @Override
        public boolean saveIfAbsent(WebhookDelivery delivery) {
            if (byEventAndEndpoint.containsKey(key(delivery))) {
                return false;
            }

            save(delivery);
            return true;
        }

        private static String key(WebhookDelivery delivery) {
            return delivery.webhookEventId().value() + "|" + delivery.endpointId().value();
        }

        @Override
        public Optional<WebhookDelivery> findByDeliveryId(MerchantId m, WebhookDeliveryId id) {
            return saved.stream().filter(d -> d.deliveryId().equals(id)).findFirst();
        }

        @Override
        public List<WebhookDelivery> findByEndpoint(MerchantId m, EndpointId endpointId, int limit) {
            return saved.stream().filter(d -> d.endpointId().equals(endpointId)).limit(limit).toList();
        }

        @Override
        public List<WebhookDelivery> findDue(Instant now, int limit) {
            return saved.stream()
                .filter(d -> d.isPending() && !d.nextAttemptAt().isAfter(now))
                .limit(limit)
                .toList();
        }

        @Override
        public Optional<WebhookDelivery> claim(WebhookDeliveryId deliveryId) {
            return saved.stream()
                .filter(d -> d.deliveryId().equals(deliveryId) && d.isPending())
                .findFirst();
        }
    }
}
