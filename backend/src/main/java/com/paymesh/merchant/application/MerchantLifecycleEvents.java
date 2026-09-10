package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantStatus;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

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

    /**
     * {@code merchant.status_changed.audited} -- the consumer half lives in the engagement service's
     * {@code RecordMerchantStatusChangeAuditHandler} (ADR-043, generalizing the mechanism ADR-042
     * section 4 built for webhook's secret rotation). {@code ChangeMerchantStatusService} used to
     * call {@code AuditRecorder.record(...)} in-process, inside this same transaction; once Audit
     * left the process, that call became impossible, so this event carries the same plaintext facts
     * the in-process {@code AuditEntry.builder(...)} call built -- the recorder on the other side
     * still does the hashing, exactly once.
     *
     * @param action the full audit action string, e.g. {@code merchant.suspended}
     */
    static OutboxEvent auditedStatusChange(
        MerchantId merchantId,
        String action,
        String operatorId,
        String reason,
        MerchantStatus before,
        MerchantStatus after,
        Instant occurredAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("actorId", operatorId);
        payload.put("resourceType", "merchant");
        payload.put("resourceId", merchantId.value());
        payload.put("reason", reason);
        payload.put("before", before.name());
        payload.put("after", after.name());

        return new OutboxEvent(
            EventId.generate(),
            merchantId,
            "MERCHANT",
            merchantId.value(),
            "merchant.status_changed.audited",
            VERSION,
            payload,
            occurredAt
        );
    }
}
