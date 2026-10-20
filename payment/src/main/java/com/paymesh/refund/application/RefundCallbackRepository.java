package com.paymesh.refund.application;

import com.paymesh.refund.domain.RefundEvent;

import java.time.Instant;
import java.util.Optional;

public interface RefundCallbackRepository {

    /**
     * Insert the callback, or report that this provider has already sent this event id.
     *
     * @return false when {@code pk_refund_callbacks} already holds it. A WRITE rather than a read:
     *     two concurrent deliveries of one callback both find nothing on a read, and only the index
     *     can decide between them.
     */
    boolean record(String provider, RefundEvent event, String payloadHash, Instant receivedAt);

    /**
     * The provider's clock on the newest callback already applied to this refund, if any. A new
     * callback older than this is refused as STALE -- ADR-012's ordering rule, applied here for the
     * same reason: a retry of an older event must not overwrite a newer decision.
     */
    Optional<Instant> latestAppliedAt(String refundId);
}
