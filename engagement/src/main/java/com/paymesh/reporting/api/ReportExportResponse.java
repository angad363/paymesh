package com.paymesh.reporting.api;

import com.paymesh.reporting.domain.ReportExport;

import java.time.Instant;

/**
 * {@code POST /api/v1/report-exports} and the JSON view of {@code GET .../{id}}.
 *
 * <p>The CSV itself is NOT here. It is the same resource in another representation, fetched with
 * {@code Accept: text/csv} on the same route -- so a merchant polling for readiness does not
 * download the file on every poll.
 *
 * @param rowCount null until COMPLETED. A zero would claim an empty export where the truth is that
 *     nothing has run yet.
 */
record ReportExportResponse(
    String id,
    String status,
    Instant from,
    Instant to,
    Integer rowCount,
    String failureReason,
    Instant requestedAt,
    Instant completedAt
) {

    static ReportExportResponse from(ReportExport export) {
        return new ReportExportResponse(
            export.id().value(),
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
