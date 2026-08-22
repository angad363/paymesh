package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditExportId;
import com.paymesh.audit.domain.AuditExportStatus;

/**
 * The CSV was asked for before it was rendered.
 *
 * <p>A 409 rather than a 404: the export exists, the representation does not yet. The operator's
 * correct move is to re-read the JSON view and wait, and a 404 would tell them to stop asking.
 */
public final class AuditExportNotReadyException extends RuntimeException {

    public AuditExportNotReadyException(AuditExportId id, AuditExportStatus status) {
        super("Audit export " + id.value() + " is " + status + " and has no file to download");
    }
}
