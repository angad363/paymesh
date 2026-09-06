package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEvent;
import com.paymesh.webhook.domain.WebhookEventId;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * Records one external event and queues a PENDING delivery per subscribed endpoint. Nothing more.
 *
 * <h2>NO HTTP HAPPENS HERE, AND THAT IS THE CAPABILITY'S INVARIANT (ADR-028 §5)</h2>
 *
 * This runs inside {@code EventDispatcher}'s transaction, which is inside the outbox relay, which
 * is downstream of a payment. A network call on this path makes a slow merchant into a stalled
 * payment. So this only ever writes rows; {@code DeliverWebhooksService} does the sending later, on
 * a timer, one transaction per delivery.
 *
 * <h2>NOTHING SUBSCRIBED MEANS NOTHING WRITTEN</h2>
 *
 * Not even the {@code webhook_events} row. An event with no subscriber has no delivery, and a row
 * that can never have one is a row that only ever grows the table.
 */
public final class FanOutWebhookEventService {

    private final WebhookEndpointRepository endpoints;
    private final WebhookEventRepository events;
    private final WebhookDeliveryRepository deliveries;
    private final Clock clock;

    public FanOutWebhookEventService(
        WebhookEndpointRepository endpoints,
        WebhookEventRepository events,
        WebhookDeliveryRepository deliveries,
        Clock clock
    ) {
        this.endpoints = endpoints;
        this.events = events;
        this.deliveries = deliveries;
        this.clock = clock;
    }

    /**
     * @param webhookEventId the id the payload will carry as its {@code id} field. Used only on the
     *     first fan-out of a given {@code sourceEventId}; a redelivery reuses the id and the bytes
     *     already stored, because a replay has to resend what was signed the first time.
     * @param payload deferred, and not as a micro-optimization: translation THROWS on a payload it
     *     cannot express, and an event nobody subscribes to must not be able to fail. Evaluated only
     *     once there is a subscriber and no stored row.
     * @return how many deliveries this call queued. Zero is the ordinary answer for a merchant with
     *     no endpoint, and also for a redelivery that found every row already there.
     */
    public int fanOut(
        WebhookEventId webhookEventId,
        MerchantId merchantId,
        String sourceEventId,
        String eventType,
        Supplier<String> payload,
        Instant occurredAt
    ) {
        List<WebhookEndpoint> subscribed = endpoints.findActiveByMerchant(merchantId).stream()
            .filter(endpoint -> endpoint.isSubscribedTo(eventType))
            .toList();

        if (subscribed.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now(clock);

        // Reusing the stored row rather than writing a second one is what makes a redelivered
        // outbox event a no-op. uq_webhook_events_source is the backstop: losing this race throws,
        // the handler's exception rolls back the inbox row, and the retry takes the found branch.
        WebhookEvent event = events.findBySourceEventId(sourceEventId).orElseGet(() ->
            events.save(WebhookEvent.translate(
                webhookEventId, merchantId.value(), sourceEventId, eventType, payload.get(),
                occurredAt, now
            ))
        );

        int queued = 0;

        for (WebhookEndpoint endpoint : subscribed) {
            boolean inserted = deliveries.saveIfAbsent(WebhookDelivery.queue(
                event.webhookEventId(), endpoint.endpointId(), merchantId.value(), now
            ));

            if (inserted) {
                queued++;
            }
        }

        return queued;
    }
}
