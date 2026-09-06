package com.paymesh.webhook.application;

import com.paymesh.webhook.domain.WebhookEndpoint;

/**
 * An endpoint plus the secret to sign for it -- THE ONLY SHAPE IN THIS CODEBASE THAT CARRIES A
 * PLAINTEXT SIGNING SECRET, and it exists only between the service and the response.
 *
 * <p>Nothing persists it, because nothing has to: the secret is derived from
 * {@code (masterKey, endpointId, secretVersion)} on demand (ADR-028 §2), so the two routes that
 * return it can simply recompute it. That property is also what lets those routes stay off the
 * {@code IdempotencyFilter} -- {@code idempotency_records.response_body} stores response bodies
 * verbatim so a retry can replay them, which would write this secret to the database in cleartext,
 * one table away from the storage the whole design exists to avoid.
 */
public record RegisteredWebhookEndpoint(WebhookEndpoint endpoint, String secret) {
}
