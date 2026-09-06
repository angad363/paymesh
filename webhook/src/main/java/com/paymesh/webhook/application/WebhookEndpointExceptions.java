package com.paymesh.webhook.application;

import com.paymesh.webhook.domain.EndpointId;
import com.paymesh.webhook.domain.WebhookDeliveryId;
import com.paymesh.webhook.domain.WebhookEndpoint;

/**
 * The business-rule failures this capability raises, in one file because each is three lines and
 * four files of three lines is four files.
 *
 * <p>All HTTP-agnostic, per the layering rule: {@code WebhookExceptionHandler} is the only place
 * that knows a status code.
 */
public final class WebhookEndpointExceptions {

    private WebhookEndpointExceptions() {
    }

    /**
     * Also the answer for another merchant's endpoint. A 403 would confirm the id exists and turn
     * the route into an oracle for enumerating other tenants' endpoints (ADR-007).
     */
    public static class WebhookEndpointNotFoundException extends RuntimeException {

        public WebhookEndpointNotFoundException(EndpointId endpointId) {
            super("No webhook endpoint " + endpointId.value());
        }
    }

    public static class WebhookDeliveryNotFoundException extends RuntimeException {

        public WebhookDeliveryNotFoundException(WebhookDeliveryId deliveryId) {
            super("No webhook delivery " + deliveryId.value());
        }
    }

    /**
     * The fan-out runs inside the event dispatcher's transaction and iterates this merchant's
     * endpoints, so the count is work on the money path and has to have a ceiling. ADR-028 §5.
     */
    public static class TooManyWebhookEndpointsException extends RuntimeException {

        public TooManyWebhookEndpointsException() {
            super(
                "A merchant may register at most "
                    + WebhookEndpoint.MAX_ENDPOINTS_PER_MERCHANT + " webhook endpoints"
            );
        }
    }

    /**
     * Subscribing to a type PayMesh does not publish would leave an endpoint ACTIVE and silently
     * receiving nothing -- a configuration that looks correct and is not. Refused at the boundary.
     */
    public static class UnpublishedEventTypeException extends RuntimeException {

        public UnpublishedEventTypeException(String eventType, java.util.Set<String> published) {
            super("PayMesh does not publish " + eventType + "; available types are " + published);
        }
    }
}
