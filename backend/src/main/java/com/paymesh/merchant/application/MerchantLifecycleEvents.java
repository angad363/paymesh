package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the merchant lifecycle events the {@code merchant_ref} projection is fed from (ADR-039).
 * The merchant capability had no outbox until now; every other service always reads a merchant's
 * status through this stream.
 *
 * <h2>The event type follows the REACHED status</h2>
 *
 * Registration lands a merchant at {@code PENDING_VERIFICATION}; activate/suspend/close reach the
 * others. One mapping serves both callers because a merchant only ever sits at
 * {@code PENDING_VERIFICATION} at registration (a status change can never return it there --
 * {@code ChangeMerchantStatusService} refuses that), so {@code PENDING_VERIFICATION -> registered}
 * is unambiguous.
 */
final class MerchantLifecycleEvents {

    /** v1 of the merchant lifecycle envelope (ADR-036 versioning rule). */
    private static final int VERSION = 1;

    private MerchantLifecycleEvents() {
    }

    static OutboxEvent of(Merchant merchant, Instant occurredAt) {
        String eventType = switch (merchant.status()) {
            case PENDING_VERIFICATION -> "merchant.registered";
            case ACTIVE -> "merchant.activated";
            case SUSPENDED -> "merchant.suspended";
            case CLOSED -> "merchant.closed";
        };

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantId", merchant.merchantId().value());
        payload.put("status", merchant.status().name());

        return new OutboxEvent(
            EventId.generate(),
            merchant.merchantId(),
            "MERCHANT",
            merchant.merchantId().value(),
            eventType,
            VERSION,
            payload,
            occurredAt
        );
    }
}
