package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

/** How the application reaches {@code audit_events}. Implemented in infrastructure. */
public interface AuditEventRepository {

    /**
     * Appends one event.
     *
     * <p>Insert only. There is no {@code update} anywhere in this interface, because the row is
     * immutable and the V36 trigger refuses one -- offering the method would be a lie the database
     * catches at runtime.
     */
    AuditEvent append(AuditEvent event);

    Optional<AuditEvent> findById(AuditEventId id);

    /** The read surface: matching events, newest first, capped at {@link AuditEventQuery#limit}. */
    List<AuditEvent> search(AuditEventQuery query);

    /**
     * The generator's source: every event in the window (optionally one tenant's), oldest first, up
     * to {@code limit}. Oldest-first so the CSV reads top-to-bottom in time; {@code limit} is the
     * export cap plus one, so "there is more than the cap" is answered by the same query.
     */
    List<AuditEvent> findInWindow(AuditWindow window, MerchantId merchantFilter, int limit);
}
