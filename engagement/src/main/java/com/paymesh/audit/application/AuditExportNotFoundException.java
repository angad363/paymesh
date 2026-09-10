package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditExportId;

/** No export with that id. Platform-wide read, so this means genuinely absent, not "not yours". */
public final class AuditExportNotFoundException extends RuntimeException {

    public AuditExportNotFoundException(AuditExportId id) {
        super("No audit export " + id.value());
    }
}
