package com.paymesh.identity.application;

import com.paymesh.identity.domain.UserId;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@code identity.user_access.audited}, the consumer half of which lives in the engagement
 * service's {@code RecordUserAccessAuditHandler} (ADR-043, generalizing the mechanism ADR-042
 * section 4 built for webhook's secret rotation). {@code ManageUserAccessService}'s private
 * {@code audit(...)} helper used to call {@code AuditRecorder.record(...)} in-process, inside the
 * same transaction as the action; once Audit left the process, that call became impossible, so this
 * event carries the same plaintext facts the in-process {@code AuditEntry.builder(...)} call built.
 *
 * <h2>THE PLATFORM-SCOPED CASE: {@code merchantId} IS GENUINELY {@code null}</h2>
 *
 * Five of the seven actions this fires for (suspend, reactivate, close, both platform-role methods)
 * name no merchant at all -- {@code AuditEntry.merchantId} has always been nullable for exactly this
 * reason. {@link OutboxEvent#merchantId()} is nullable for the same reason (ADR-043; see the
 * monolith's V40 migration), so a platform-scoped grant is not forced into a synthetic tenant it
 * does not have. The two merchant-scoped actions (access_granted/access_revoked) still carry a real
 * one.
 */
final class UserAccessAuditEvents {

    /** v1 of the identity user-access audit envelope (ADR-036 versioning rule). */
    private static final int VERSION = 1;

    private UserAccessAuditEvents() {
    }

    static OutboxEvent of(
        String action,
        String operatorId,
        MerchantId merchantId,
        UserId target,
        String reason,
        Instant occurredAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("actorId", operatorId);
        payload.put("resourceType", "user");
        payload.put("resourceId", target.value());
        payload.put("reason", reason);

        return new OutboxEvent(
            EventId.generate(),
            merchantId,
            "USER",
            target.value(),
            "identity.user_access.audited",
            VERSION,
            payload,
            occurredAt
        );
    }
}
