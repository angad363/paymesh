package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;
import com.paymesh.audit.domain.AuditExportStatus;

/**
 * Reads one export back: its status, or its file. Platform-wide -- an audit export has no tenant
 * owner, so any platform admin may read any export (ADR-035).
 */
public final class GetAuditExportService {

    private final AuditExportRepository exports;

    public GetAuditExportService(AuditExportRepository exports) {
        this.exports = exports;
    }

    public AuditExport get(AuditExportId id) {
        return exports.findById(id).orElseThrow(() -> new AuditExportNotFoundException(id));
    }

    /**
     * The CSV itself.
     *
     * @throws AuditExportNotReadyException when the export has not been rendered -- checked here, not
     *     in the controller, so a second caller cannot forget the rule
     */
    public String download(AuditExportId id) {
        AuditExport export = get(id);

        if (export.status() != AuditExportStatus.COMPLETED) {
            throw new AuditExportNotReadyException(id, export.status());
        }

        return export.content();
    }
}
