package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookEndpoint;

import java.util.List;
import java.util.Optional;

/** How the application reaches webhook_endpoints. Implemented in infrastructure. */
public interface WebhookEndpointRepository {

    WebhookEndpoint save(WebhookEndpoint endpoint);

    Optional<WebhookEndpoint> findByEndpointId(MerchantId merchantId, EndpointId endpointId);

    List<WebhookEndpoint> findByMerchant(MerchantId merchantId);

    /**
     * The fan-out query. ACTIVE only, and filtered on subscriptions by the caller because a jsonb
     * array is not a JPA predicate -- which is what {@code MAX_ENDPOINTS_PER_MERCHANT} bounds
     * (ADR-028 §5): this runs inside the event dispatcher's transaction.
     */
    List<WebhookEndpoint> findActiveByMerchant(MerchantId merchantId);

    long countByMerchant(MerchantId merchantId);
}
