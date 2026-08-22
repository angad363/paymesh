package com.paymesh.reporting.api;

import java.time.Instant;

/**
 * {@code POST /api/v1/report-exports}.
 *
 * <h2>NO reportType FIELD, DELIBERATELY</h2>
 *
 * There is one export: the merchant's facts in a window, which is the data BOTH summaries are
 * computed from. A type discriminator would exist to select between a CSV of the numbers the JSON
 * endpoints already return and this one, and the first of those is not worth a second writer.
 *
 * <h2>Instants, not dates, and no Bean Validation annotations</h2>
 *
 * The window's rules -- ends after it starts, no longer than a year -- belong to
 * {@code ReportWindow}, which every caller of this capability goes through. Restating them as
 * {@code @NotNull} here would put half the rule at the boundary and half in the domain, and the two
 * halves would drift. Both fields are optional and default the same way the query parameters do.
 */
record CreateReportExportRequest(Instant from, Instant to) {
}
