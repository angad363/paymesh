package com.paymesh.webhook.application;

/**
 * A second endpoint at a URL this merchant already registered.
 *
 * <p>Refused rather than tolerated because two endpoints at one URL fan out twice to the same
 * place: the merchant's handler sees every event doubled, and its idempotency is the only thing
 * standing between that and a doubled fulfilment.
 */
public class WebhookEndpointAlreadyExistsException extends RuntimeException {

    private final String url;

    public WebhookEndpointAlreadyExistsException(String url) {
        super("A webhook endpoint is already registered at " + url);

        this.url = url;
    }

    public String url() {
        return url;
    }
}
