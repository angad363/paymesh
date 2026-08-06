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

    /**
     * The dispatcher's candidates: PENDING rows whose {@code next_attempt_at} has passed, oldest
     * first.
     *
     * <p>IDS, NOT AGGREGATES, AND NOT AS A MICRO-OPTIMISATION. Open item 2 in
     * {@code docs/project-status.md} is the shape being avoided: a sweep that maps its candidate
     * rows outside the per-item try/catch, where one unmappable row disables it permanently and
     * silently. Returning ids means there is nothing to map here, and each row is rehydrated inside
     * its own transaction where a failure costs one delivery instead of the batch.
     *
     * <p><b>RAW STRINGS, and that is the half this originally got wrong.</b> This returned
     * {@code List<WebhookDeliveryId>}, and {@code WebhookDeliveryId.from} validates -- so the
     * throwing call simply moved from the mapper to the adapter, still outside the boundary, and
     * {@code whd_} ids carry no format CHECK. The parse belongs in the dispatcher's try with
     * everything else that can fail.
     */
    List<String> findDue(Instant now, int limit);

    /**
     * Locks one candidate for this dispatcher, re-checking that it is still PENDING.
     *
     * @return empty when another dispatcher holds it or it stopped being PENDING since the candidate
     *     list was read -- both no-ops, not errors
     */
    Optional<WebhookDelivery> claim(WebhookDeliveryId deliveryId);
}
