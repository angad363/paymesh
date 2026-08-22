package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;

import java.util.List;

/**
 * The platform-staff read: recent audit events matching a filter, newest first.
 *
 * <p>No tenant scope, deliberately -- this is support and compliance tooling that reads across
 * tenants (ADR-035). The filter's {@code merchantId} narrows the result; it does not fence the
 * caller. The controller enforces {@code PLATFORM_ADMIN}, which is what makes reading another
 * tenant's history legitimate here where it would be a breach on an {@code /api/} route.
 *
 * <p>Capped-limit, newest-first, no cursor -- the same shape the webhook deliveries endpoint has.
 * Keyset pagination is the upgrade when a compliance reviewer needs to page past the cap.
 */
// ponytail: capped list, no cursor -- add keyset (occurred_at, id) when paging past the cap matters.
public final class ListAuditEventsService {

    private final AuditEventRepository events;

    public ListAuditEventsService(AuditEventRepository events) {
        this.events = events;
    }

    public List<AuditEvent> list(AuditEventQuery query) {
        return events.search(query);
    }
}
