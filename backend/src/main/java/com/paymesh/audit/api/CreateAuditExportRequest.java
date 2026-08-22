package com.paymesh.audit.api;

import java.time.Instant;

/**
 * {@code POST /internal/v1/audit-exports}.
 *
 * <h2>merchantId IS A FILTER, NOT AN OWNER</h2>
 *
 * Present, it narrows the export to one tenant; absent, the export covers every tenant in the
 * window. Either way the export belongs to the platform, not the merchant -- who requested it is
 * taken from the token, never this body.
 *
 * <h2>Instants, not dates, and no Bean Validation annotations</h2>
 *
 * The window's rules -- ends after it starts, no longer than a year -- belong to {@code AuditWindow},
 * which every caller goes through. Restating them here would split the rule in two halves that
 * drift. All three fields are optional; the window defaults to the last thirty days.
 */
record CreateAuditExportRequest(String merchantId, Instant from, Instant to) {
}
