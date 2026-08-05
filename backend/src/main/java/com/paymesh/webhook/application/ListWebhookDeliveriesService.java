package com.paymesh.webhook.application;

import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.webhook.application.WebhookEndpointExceptions.WebhookEndpointNotFoundException;
import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDelivery;

import java.util.List;

/**
 * What happened to one endpoint's deliveries. The debugging surface, and the reason
 * {@code last_status_code} and {@code last_response_excerpt} are columns.
 *
 * <p>Newest first and capped: a merchant debugging a failure wants the last few, and an unbounded
 * list of a busy endpoint's history is a denial of service against PayMesh, requested politely.
 */
public final class ListWebhookDeliveriesService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    private final WebhookDeliveryRepository deliveries;
    private final WebhookEndpointRepository endpoints;

    public ListWebhookDeliveriesService(
        WebhookDeliveryRepository deliveries, WebhookEndpointRepository endpoints
    ) {
        this.deliveries = deliveries;
        this.endpoints = endpoints;
    }

    /**
     * The endpoint is resolved first so another tenant's id answers 404 rather than an empty list.
     * An empty list would confirm the endpoint exists and belongs to nobody the caller can see,
     * which is one bit more than they should get (ADR-007).
     */
    public List<WebhookDelivery> list(MerchantId merchantId, EndpointId endpointId, Integer limit) {
        if (endpoints.findByEndpointId(merchantId, endpointId).isEmpty()) {
            throw new WebhookEndpointNotFoundException(endpointId);
        }

        return deliveries.findByEndpoint(merchantId, endpointId, cap(limit));
    }

    private static int cap(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }

        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }

        return Math.min(limit, MAX_LIMIT);
    }
}
