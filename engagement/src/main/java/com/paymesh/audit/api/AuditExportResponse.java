package com.paymesh.audit.api;

import com.paymesh.audit.domain.AuditExport;

import java.time.Instant;

/**
 * {@code POST /internal/v1/audit-exports} and the JSON view of {@code GET .../{id}}.
 *
 * <p>The CSV itself is NOT here -- it is the same resource in another representation, fetched with
 * {@code Accept: text/csv} on the same route, so an operator polling for readiness does not download
 * the file on every poll.
 *
 * @param rowCount null until COMPLETED; a zero would claim an empty export where the truth is that
 *     nothing has run yet
 */
record AuditExportResponse(
    String id,
    String requestedBy,
    String merchantFilter,
    String status,
    Instant from,
    Instant to,
    Integer rowCount,
    String failureReason,
    Instant requestedAt,
    Instant completedAt
) {

    static AuditExportResponse from(AuditExport export) {
        return new AuditExportResponse(
            export.id().value(),
            export.requestedBy(),
            export.merchantFilter() == null ? null : export.merchantFilter().value(),
            export.status().name(),
            export.window().from(),
            export.window().to(),
            export.rowCount(),
            export.failureReason(),
            export.requestedAt(),
            export.completedAt()
        );
    }
}
