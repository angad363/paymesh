package com.paymesh.webhook.application;

import java.util.List;

/**
 * A PATCH: both fields are optional and null means "leave it alone".
 *
 * @param subscriptions the complete new list when present -- a replacement, not an addition, so a
 *     merchant can unsubscribe without a second verb
 * @param status {@code ACTIVE} or {@code DISABLED}. Requested as a value here rather than as two
 *     routes because it is one field with two settings; the aggregate still moves through
 *     {@code enable()} / {@code disable()} rather than having a status written into it
 */
public record UpdateWebhookEndpointCommand(List<String> subscriptions, String status) {
}
