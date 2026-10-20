package com.paymesh.payment.application;

import com.paymesh.payment.domain.ProviderEvent;

/**
 * One judged delivery, as the application layer receives it.
 *
 * @param provider    which provider spoke. From the path, not the body: a body claiming to be from
 *                    a different provider than the route it arrived on would be a second, weaker
 *                    identity for a value the deduplication key depends on.
 * @param event       the parsed body, already an allowlist (see {@link ProviderEvent}).
 * @param payloadHash SHA-256 of the RAW body, hex, computed by the signature filter before anything
 *                    parsed it. It cannot be recomputed here -- by this point the bytes are gone --
 *                    which is why it is carried rather than derived.
 */
public record RecordProviderCallbackCommand(
    String provider,
    ProviderEvent event,
    String payloadHash
) {

    public RecordProviderCallbackCommand {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("Provider cannot be blank");
        }

        if (event == null) {
            throw new IllegalArgumentException("Provider event cannot be null");
        }

        if (payloadHash == null || payloadHash.isBlank()) {
            throw new IllegalArgumentException("Payload hash cannot be blank");
        }
    }
}
