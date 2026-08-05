package com.paymesh.webhook.application;

import java.util.List;

/**
 * No {@code merchantId} field, on purpose: the tenant comes from the verified token at the
 * controller, so a cross-tenant registration is not something the API can express.
 */
public record RegisterWebhookEndpointCommand(String url, List<String> subscriptions) {
}
