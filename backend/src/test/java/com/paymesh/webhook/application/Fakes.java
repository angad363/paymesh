package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The collaborators the webhook service tests share. Same shape and same reason as Order's,
 * Payment's and Identity's: three copies of a transaction-counting {@code TransactionTemplate}
 * would be three chances for one of them to stop counting.
 */
final class Fakes {

    private Fakes() {
    }

    /**
     * Runs the callback straight through and counts the calls. It cannot roll anything back -- a
     * plain JUnit test has no database -- so it proves the boundary was entered, not that it holds.
     */
    static final class ImmediateTransactions extends TransactionTemplate {

        private int executions;

        int executions() {
            return executions;
        }

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            executions++;

            return action.doInTransaction(new SimpleTransactionStatus());
        }
    }

    /** Last write wins, keyed by endpoint id, which is how the real table behaves. */
    static final class FakeEndpoints implements WebhookEndpointRepository {

        private final Map<String, WebhookEndpoint> stored = new LinkedHashMap<>();

        WebhookEndpoint add(WebhookEndpoint endpoint) {
            stored.put(endpoint.endpointId().value(), endpoint);

            return endpoint;
        }

        @Override
        public WebhookEndpoint save(WebhookEndpoint endpoint) {
            return add(endpoint);
        }

        @Override
        public Optional<WebhookEndpoint> findByEndpointId(MerchantId merchantId, EndpointId id) {
            return Optional.ofNullable(stored.get(id.value()))
                .filter(endpoint -> endpoint.merchantId().equals(merchantId.value()));
        }

        @Override
        public List<WebhookEndpoint> findByMerchant(MerchantId merchantId) {
            return stored.values().stream()
                .filter(endpoint -> endpoint.merchantId().equals(merchantId.value()))
                .toList();
        }

        @Override
        public List<WebhookEndpoint> findActiveByMerchant(MerchantId merchantId) {
            return findByMerchant(merchantId).stream().filter(WebhookEndpoint::isActive).toList();
        }

        @Override
        public long countByMerchant(MerchantId merchantId) {
            return findByMerchant(merchantId).size();
        }
    }

    static final class FakeEvents implements WebhookEventRepository {

        private final List<WebhookEvent> saved = new ArrayList<>();

        List<WebhookEvent> saved() {
            return saved;
        }

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

    /**
     * Keyed the way {@code uq_webhook_deliveries_event_endpoint} is, which is the point of it, and
     * separately by delivery id so a save is an update rather than a second row.
     */
    static final class FakeDeliveries implements WebhookDeliveryRepository {

        private final Map<String, WebhookDelivery> byId = new LinkedHashMap<>();
        private int inserts;
        private String poisonedCandidate;

        int inserts() {
            return inserts;
        }

        /**
         * Puts one raw id at the head of the candidate list that no stored delivery matches.
         * <p>
         * The only way to reproduce a candidate the dispatcher cannot parse, because every id this
         * fake would otherwise return came from a {@code WebhookDeliveryId} that already validated.
         * {@code webhook_deliveries.webhook_delivery_id} carries no format CHECK, so the real query
         * can return one of these.
         */
        void poisonCandidateList(String rawId) {
            this.poisonedCandidate = rawId;
        }

        List<WebhookDelivery> all() {
            return List.copyOf(byId.values());
        }

        WebhookDelivery only() {
            return byId.values().iterator().next();
        }

        @Override
        public WebhookDelivery save(WebhookDelivery delivery) {
            byId.put(delivery.deliveryId().value(), delivery);

            return delivery;
        }

        @Override
        public boolean saveIfAbsent(WebhookDelivery delivery) {
            boolean present = byId.values().stream().anyMatch(existing ->
                existing.webhookEventId().equals(delivery.webhookEventId())
                    && existing.endpointId().equals(delivery.endpointId())
            );

            if (present) {
                return false;
            }

            inserts++;
            save(delivery);

            return true;
        }

        @Override
        public Optional<WebhookDelivery> findByDeliveryId(MerchantId m, WebhookDeliveryId id) {
            return Optional.ofNullable(byId.get(id.value()))
                .filter(delivery -> delivery.merchantId().equals(m.value()));
        }

        @Override
        public List<WebhookDelivery> findByEndpoint(MerchantId m, EndpointId endpointId, int limit) {
            return byId.values().stream()
                .filter(d -> d.endpointId().equals(endpointId))
                .limit(limit)
                .toList();
        }

        @Override
        public List<String> findDue(Instant now, int limit) {
            Stream<String> real = byId.values().stream()
                .filter(d -> d.isPending() && !d.nextAttemptAt().isAfter(now))
                .limit(limit)
                .map(d -> d.deliveryId().value());

            return poisonedCandidate == null
                ? real.toList()
                : Stream.concat(Stream.of(poisonedCandidate), real).toList();
        }

        @Override
        public Optional<WebhookDelivery> claim(WebhookDeliveryId deliveryId) {
            return Optional.ofNullable(byId.get(deliveryId.value())).filter(WebhookDelivery::isPending);
        }
    }

    /** Answers whatever the test told it to, and remembers what it was asked to send. */
    static final class ScriptedSender implements WebhookSender {

        private final List<String> sent = new ArrayList<>();
        private final List<WebhookEndpoint> endpoints = new ArrayList<>();
        private WebhookSendResult next = WebhookSendResult.accepted(200, "ok");

        List<String> sent() {
            return sent;
        }

        List<WebhookEndpoint> endpoints() {
            return endpoints;
        }

        void answer(WebhookSendResult result) {
            this.next = result;
        }

        @Override
        public WebhookSendResult send(WebhookEndpoint endpoint, String payload) {
            endpoints.add(endpoint);
            sent.add(payload);

            return next;
        }
    }
}
