package com.paymesh.webhook.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A PATCH, so both fields are nullable and null means "leave it alone". An empty subscriptions list
 * is a different thing from an absent one and is refused by the aggregate: an endpoint subscribed to
 * nothing sits ACTIVE and silently receives nothing forever.
 */
public record UpdateWebhookEndpointRequest(

    List<@NotBlank @Size(max = 60) String> subscriptions,

    @Size(max = 20)
    String status
) {
}
