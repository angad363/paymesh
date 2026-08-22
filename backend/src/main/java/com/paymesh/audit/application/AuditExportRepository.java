package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;

import java.util.List;
import java.util.Optional;

/** How the application reaches {@code audit_exports}. Implemented in infrastructure. */
public interface AuditExportRepository {

    AuditExport save(AuditExport export);

    /**
     * By id, with no tenant clause. An audit export is not owned by a merchant -- any platform admin
     * may read any export (ADR-035), so "found" is the whole question.
     */
    Optional<AuditExport> findById(AuditExportId id);

    /** The generator's candidate list: PENDING ids, oldest first. Unlocked; {@link #claim} locks. */
    List<String> findPending(int limit);

    /**
     * Re-reads one export under a row lock, skipping it if another generator holds it.
     *
     * @return empty when the row is locked elsewhere or is no longer PENDING
     */
    Optional<AuditExport> claim(AuditExportId id);
}
