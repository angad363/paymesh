package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** How the application reaches webhook_deliveries. Implemented in infrastructure. */
public interface WebhookDeliveryRepository {

    WebhookDelivery save(WebhookDelivery delivery);

    /**
     * Queues one delivery unless {@code uq_webhook_deliveries_event_endpoint} already has it.
     *
     * @return false when the pair already existed, which is the normal outcome of a redelivered
     *     outbox event and is not an error
     */
    boolean saveIfAbsent(WebhookDelivery delivery);

    Optional<WebhookDelivery> findByDeliveryId(MerchantId merchantId, WebhookDeliveryId deliveryId);

    List<WebhookDelivery> findByEndpoint(MerchantId merchantId, EndpointId endpointId, int limit);

    /** The dispatcher's candidates: PENDING rows whose {@code next_attempt_at} has passed, oldest first. */
    List<WebhookDelivery> findDue(Instant now, int limit);

    /**
     * Locks one candidate for this dispatcher, re-checking that it is still PENDING.
     *
     * @return empty when another dispatcher holds it or it stopped being PENDING since the candidate
     *     list was read -- both no-ops, not errors
     */
    Optional<WebhookDelivery> claim(WebhookDeliveryId deliveryId);
}
