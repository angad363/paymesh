package com.paymesh.audit.api;

import com.paymesh.audit.domain.AuditEvent;

import java.time.Instant;

/**
 * The JSON view of one audit event.
 *
 * <p>Carries the hashes, not values -- there is nothing else to carry, because the table never held
 * a plaintext secret or address. A reviewer comparing {@code beforeHash} to {@code afterHash} can
 * see that state changed; they were never meant to read what it changed from.
 */
record AuditEventResponse(
    String id,
    String actorType,
    String actorId,
    String merchantId,
    String action,
    String resourceType,
    String resourceId,
    String reason,
    String beforeHash,
    String afterHash,
    String ipHash,
    Instant occurredAt
) {

    static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(
            event.id().value(),
            event.actorType().name(),
            event.actorId(),
            event.merchantId() == null ? null : event.merchantId().value(),
            event.action(),
            event.resourceType(),
            event.resourceId(),
            event.reason(),
            event.beforeHash(),
            event.afterHash(),
            event.ipHash(),
            event.occurredAt()
        );
    }
}
