package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;

/** Reads one audit event by id, for a support engineer following a link. */
public final class GetAuditEventService {

    private final AuditEventRepository events;

    public GetAuditEventService(AuditEventRepository events) {
        this.events = events;
    }

    public AuditEvent get(AuditEventId id) {
        return events.findById(id).orElseThrow(() -> new AuditEventNotFoundException(id));
    }
}
