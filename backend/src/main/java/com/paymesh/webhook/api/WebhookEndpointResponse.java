package com.paymesh.webhook.api;

import com.paymesh.webhook.application.RegisteredWebhookEndpoint;
import com.paymesh.webhook.domain.WebhookEndpoint;

import java.time.Instant;
import java.util.List;

/**
 * @param secret PRESENT EXACTLY TWICE IN THIS API -- on create and on rotate -- and null everywhere
 *     else. Not because it is hard to fetch again but because showing it is a decision: a merchant
 *     who can read it from a list endpoint has a secret that leaks through every audit log and
 *     screen share that ever touches that route. Stripe, GitHub and Shopify all show it once.
 *     <p>
 *     It is derivable, so a lost secret is recovered by rotating rather than by looking it up.
 * @param secretVersion what the merchant passes back to {@code rotate-secret}, which is what makes
 *     a retried rotation idempotent
 */
public record WebhookEndpointResponse(
    String id,
    String url,
    List<String> subscriptions,
    String status,
    int secretVersion,
    String secret,
    Instant previousSecretExpiresAt,
    Instant createdAt,
    Instant updatedAt
) {

    public static WebhookEndpointResponse from(WebhookEndpoint endpoint) {
        return of(endpoint, null);
    }

    public static WebhookEndpointResponse from(RegisteredWebhookEndpoint registered) {
        return of(registered.endpoint(), registered.secret());
    }

    private static WebhookEndpointResponse of(WebhookEndpoint endpoint, String secret) {
        return new WebhookEndpointResponse(
            endpoint.endpointId().value(),
            endpoint.url(),
            List.copyOf(endpoint.subscriptions()),
            endpoint.status().name(),
            endpoint.secretVersion(),
            secret,
            endpoint.previousSecretExpiresAt(),
            endpoint.createdAt(),
            endpoint.updatedAt()
        );
    }
}
