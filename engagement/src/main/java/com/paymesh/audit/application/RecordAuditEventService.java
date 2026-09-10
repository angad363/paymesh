package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;

/**
 * Appends one already-built {@link AuditEvent}.
 *
 * <p>Thin on purpose. The interesting work -- turning an {@code AuditEntry} into an event, hashing
 * its plaintext, minting the id -- lives in the {@code AuditRecorder} adapter that calls this. This
 * service exists so the append is a named application operation the read side and the tests can
 * reason about, and so the adapter depends on an application port rather than the repository.
 *
 * <h2>NO TRANSACTION OF ITS OWN</h2>
 *
 * The append runs inside the caller's transaction -- the privileged action's -- so the audit row and
 * the action commit together (ADR-035). Opening a transaction here would break exactly that: a
 * committed action with a rolled-back audit row, or the reverse.
 */
public final class RecordAuditEventService {

    private final AuditEventRepository events;

    public RecordAuditEventService(AuditEventRepository events) {
        this.events = events;
    }

    public AuditEvent record(AuditEvent event) {
        return events.append(event);
    }
}
