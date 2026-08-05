package com.paymesh.webhook.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Boundary validation only. Whether the URL is really {@code https} with no userinfo is the
 * aggregate's business, and whether the event types are ones PayMesh publishes is the service's --
 * both need knowledge an annotation does not have.
 */
public record RegisterWebhookEndpointRequest(

    @NotBlank
    @Size(max = 2048)
    String url,

    @NotEmpty
    List<@NotBlank @Size(max = 60) String> subscriptions
) {
}
