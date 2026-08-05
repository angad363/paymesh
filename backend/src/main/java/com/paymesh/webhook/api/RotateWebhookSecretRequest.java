package com.paymesh.webhook.api;

import jakarta.validation.constraints.Min;

/**
 * @param fromVersion the version the caller believes is current. Required rather than optional
 *     because it is what makes a retried rotation idempotent -- without it, a client that lost the
 *     response and asked again would bump twice and invalidate the secret it never received.
 */
public record RotateWebhookSecretRequest(

    @Min(1)
    int fromVersion
) {
}
