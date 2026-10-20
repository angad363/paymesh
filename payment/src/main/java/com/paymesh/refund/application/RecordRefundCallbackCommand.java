package com.paymesh.refund.application;

import com.paymesh.refund.domain.RefundEvent;

/**
 * @param provider uppercased by the controller, part of the dedup key
 * @param payloadHash SHA-256 of the RAW body, taken in the signature filter where the raw bytes
 *     still exist. A hash of a re-serialized record would be a hash of Jackson's formatting.
 */
public record RecordRefundCallbackCommand(String provider, RefundEvent event, String payloadHash) {

    public RecordRefundCallbackCommand {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("A refund callback must name its provider");
        }

        if (event == null) {
            throw new IllegalArgumentException("A refund callback must carry an event");
        }

        if (payloadHash == null || !payloadHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("A refund callback payload hash must be 64 hex characters");
        }
    }
}
