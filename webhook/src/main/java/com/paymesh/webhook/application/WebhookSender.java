package com.paymesh.webhook.application;

import com.paymesh.webhook.domain.WebhookEndpoint;

/**
 * Signs one payload and puts it on the wire. The seam that keeps
 * {@link DeliverWebhooksService} testable without a socket.
 *
 * <p>The endpoint rather than the URL, because the signature depends on
 * {@code WebhookEndpoint.signingVersions(now)} -- one version normally, two inside a rotation
 * window. The implementation holds the master key; nothing above this line has ever seen a secret.
 */
public interface WebhookSender {

    WebhookSendResult send(WebhookEndpoint endpoint, String payload);

    /**
     * @param statusCode null when nothing answered. A refused connection, a DNS failure, a read
     *     timeout and a blocked address all land here, and the absence of a code is itself the
     *     information.
     * @param excerpt what came back, or why nothing did. Capped by
     *     {@code WebhookDelivery.MAX_RESPONSE_EXCERPT} on the way into the database.
     */
    record WebhookSendResult(boolean accepted, Integer statusCode, String excerpt) {

        public static WebhookSendResult accepted(int statusCode, String excerpt) {
            return new WebhookSendResult(true, statusCode, excerpt);
        }

        public static WebhookSendResult refused(Integer statusCode, String excerpt) {
            return new WebhookSendResult(false, statusCode, excerpt);
        }
    }
}
