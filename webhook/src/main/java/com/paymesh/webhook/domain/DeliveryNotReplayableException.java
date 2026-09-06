package com.paymesh.webhook.domain;

/**
 * Replay was asked for on a delivery the dispatcher is still going to retry.
 *
 * <p>Allowing it would mean two POSTs to the merchant for one event: the replay's and the retry
 * the schedule already had queued. A merchant's endpoint is supposed to be idempotent, but "we send
 * duplicates and you deal with it" is not a contract PayMesh should write on purpose.
 */
public class DeliveryNotReplayableException extends RuntimeException {

    private final transient WebhookDeliveryId deliveryId;

    public DeliveryNotReplayableException(WebhookDeliveryId deliveryId) {
        super(
            "Delivery " + deliveryId.value() + " is still pending; it will be retried on schedule"
        );

        this.deliveryId = deliveryId;
    }

    public WebhookDeliveryId deliveryId() {
        return deliveryId;
    }
}
