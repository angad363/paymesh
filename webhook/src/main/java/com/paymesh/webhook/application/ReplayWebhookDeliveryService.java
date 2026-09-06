package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookDeliveryNotFoundException;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;

/**
 * Re-queues a terminal delivery. The merchant's own remedy for an outage on their side.
 *
 * <p>The payload is untouched -- it lives on the immutable {@code webhook_events} row -- so a replay
 * resends byte-identical content. Only the signature's timestamp differs, because it is a freshness
 * stamp taken at delivery rather than a property of the event.
 *
 * <p>THE ONE ROUTE IN THIS CAPABILITY ON {@code IdempotentRoutes}. Its response carries no secret,
 * and a retried replay of a delivery that has already gone back to PENDING would otherwise answer
 * 409 -- the wrong answer to a network retry of a request that worked.
 */
public final class ReplayWebhookDeliveryService {

    private final WebhookDeliveryRepository deliveries;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ReplayWebhookDeliveryService(
        WebhookDeliveryRepository deliveries, TransactionTemplate transactions, Clock clock
    ) {
        this.deliveries = deliveries;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @param endpointId from the route, and checked rather than decorative: the delivery is
     *     addressable under one endpoint and a URL that names a different one is answered 404, not
     *     quietly honoured. Both are the caller's own, so this is coherence rather than security --
     *     but a path that can lie about what it addresses is a path nobody can reason from.
     */
    public WebhookDelivery replay(
        MerchantId merchantId, EndpointId endpointId, WebhookDeliveryId deliveryId
    ) {
        return transactions.execute(status -> {
            WebhookDelivery delivery = deliveries.findByDeliveryId(merchantId, deliveryId)
                .filter(found -> found.endpointId().equals(endpointId))
                .orElseThrow(() -> new WebhookDeliveryNotFoundException(deliveryId));

            return deliveries.save(delivery.replay(Instant.now(clock)));
        });
    }
}
