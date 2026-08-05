package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Sends the deliveries that are due. THE ONLY PLACE IN THIS CAPABILITY THAT MAKES A NETWORK CALL,
 * and it is deliberately as far from a payment as the design can put it.
 *
 * <h2>One transaction per delivery</h2>
 *
 * Each row is claimed with {@code SELECT ... FOR UPDATE SKIP LOCKED}, sent, and its result
 * committed on its own. One merchant hanging cannot roll back the deliveries beside it, and a
 * second dispatcher takes different rows rather than queueing behind this one's socket.
 * <p>
 * <b>The accepted cost, stated rather than hidden</b> (the same one ADR-017 records for the
 * simulator): the HTTP call happens while the row lock is held, so a hung merchant holds one lock
 * and one connection for the read timeout. The shape without that property is
 * claim-send-acknowledge with a lease expiry, which is a third state and an expiry sweep. The
 * mitigations are the short read timeout and the small batch, and both are properties.
 *
 * <h2>Two counters, and confusing them is a factor of five</h2>
 *
 * A failed attempt reschedules the delivery. Only a delivery that spends its <i>whole</i> budget --
 * five attempts over about eight and a half hours -- moves the endpoint's consecutive-failure streak,
 * by one. Twenty of those in a row disable the endpoint. ADR-028 §6.
 */
public final class DeliverWebhooksService {

    private static final Logger log = LoggerFactory.getLogger(DeliverWebhooksService.class);

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRepository events;
    private final WebhookSender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;

    public DeliverWebhooksService(
        WebhookDeliveryRepository deliveries,
        WebhookEndpointRepository endpoints,
        WebhookEventRepository events,
        WebhookSender sender,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize
    ) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
        this.events = events;
        this.sender = sender;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * One pass over the due deliveries.
     * <p>
     * The candidate list is read unlocked and each row is then re-read under its own lock, rather
     * than the whole batch being held for the duration of every HTTP call in it.
     */
    public DispatchResult dispatch() {
        List<WebhookDeliveryId> due = deliveries.findDue(Instant.now(clock), batchSize);

        int delivered = 0;
        int retried = 0;
        int dead = 0;
        int errored = 0;

        for (WebhookDeliveryId deliveryId : due) {
            Attempt attempt;

            try {
                attempt = deliverOne(deliveryId);
            } catch (RuntimeException failure) {
                // ONE BAD ROW MUST NOT DISABLE THE PASS. An optimistic-lock collision with a
                // concurrent PATCH of the endpoint, a row the mapper cannot rehydrate, a
                // constraint nobody predicted: each costs one delivery, which is still PENDING
                // and will be picked up next pass. Open item 2 in docs/project-status.md is the
                // same mistake made in two existing sweeps -- there the mapping sits OUTSIDE the
                // per-item boundary, so one unmappable row stops the sweep permanently and
                // silently. That is why findDue returns ids.
                log.error("Webhook delivery {} threw and will be retried", deliveryId.value(), failure);

                errored++;

                continue;
            }

            switch (attempt) {
                case DELIVERED -> delivered++;
                case RETRIED -> retried++;
                case DEAD -> dead++;
                case GONE -> {
                    // Claimed by a concurrent dispatcher, or no longer PENDING, between the
                    // candidate read and the lock. SKIP LOCKED exists so that is a no-op.
                }
            }
        }

        return new DispatchResult(due.size(), delivered, retried, dead, errored);
    }

    private Attempt deliverOne(WebhookDeliveryId deliveryId) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            WebhookDelivery delivery = deliveries.claim(deliveryId).orElse(null);

            if (delivery == null) {
                return Attempt.GONE;
            }

            MerchantId merchantId = MerchantId.from(delivery.merchantId());

            WebhookEndpoint endpoint = endpoints
                .findByEndpointId(merchantId, delivery.endpointId())
                .orElseThrow(() -> new IllegalStateException(
                    "Delivery " + deliveryId.value() + " names endpoint "
                        + delivery.endpointId().value() + ", which fk_webhook_deliveries_endpoint "
                        + "says must exist"
                ));

            WebhookEvent event = events.findById(delivery.webhookEventId())
                .orElseThrow(() -> new IllegalStateException(
                    "Delivery " + deliveryId.value() + " names event "
                        + delivery.webhookEventId().value() + ", which fk_webhook_deliveries_event "
                        + "says must exist"
                ));

            // A DISABLED endpoint is not sent to, but its backlog is not left PENDING forever
            // either: each pass records a failed attempt, so the queue drains to FAILED on the
            // ordinary schedule and nothing sits scheduled with nobody willing to send it.
            WebhookSender.WebhookSendResult result = endpoint.isActive()
                ? sender.send(endpoint, event.payload())
                : WebhookSender.WebhookSendResult.refused(null, "Endpoint is disabled");

            if (result.accepted()) {
                deliveries.save(delivery.succeeded(result.statusCode(), result.excerpt(), now));

                // Saved only when it changes something. recordSuccess returns the same aggregate at
                // a zero streak, and writing it anyway would bump the optimistic version for
                // nothing -- straight into a concurrent PATCH of the same endpoint.
                if (endpoint.consecutiveFailures() > 0) {
                    endpoints.save(endpoint.recordSuccess(now));
                }

                return Attempt.DELIVERED;
            }

            WebhookDelivery failed =
                delivery.attemptFailed(result.statusCode(), result.excerpt(), now);

            deliveries.save(failed);

            if (!failed.isDead()) {
                return Attempt.RETRIED;
            }

            WebhookEndpoint afterDeath = endpoint.recordDeadDelivery(now);

            endpoints.save(afterDeath);

            if (afterDeath.isActive()) {
                log.warn(
                    "Webhook delivery {} to endpoint {} died after {} attempts, last status {}",
                    failed.deliveryId().value(), endpoint.endpointId().value(),
                    failed.attempts(), result.statusCode()
                );
            } else {
                log.warn(
                    "Webhook endpoint {} disabled after {} consecutive dead deliveries",
                    endpoint.endpointId().value(), afterDeath.consecutiveFailures()
                );
            }

            return Attempt.DEAD;
        });
    }

    private enum Attempt {
        DELIVERED,
        RETRIED,
        DEAD,
        GONE
    }

    /**
     * What one pass did. Counted so the scheduled bean can log something worth reading.
     *
     * @param errored threw and was logged; the row is still PENDING and the next pass retries it
     */
    public record DispatchResult(int examined, int delivered, int retried, int dead, int errored) {
    }
}
