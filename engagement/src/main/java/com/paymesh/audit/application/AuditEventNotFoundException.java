package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEventId;

/**
 * No audit event with that id.
 *
 * <p>No tenant clause, unlike the merchant-facing not-founds: this read is platform-wide, so "not
 * found" here means genuinely absent rather than "not yours".
 */
public final class AuditEventNotFoundException extends RuntimeException {

    public AuditEventNotFoundException(AuditEventId id) {
        super("No audit event " + id.value());
    }
}
