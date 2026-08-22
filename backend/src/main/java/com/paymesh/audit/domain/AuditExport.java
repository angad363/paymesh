package com.paymesh.audit.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * A platform operator's request for a CSV of audit events in a window, and the file once it exists
 * (SDD 19.3, ADR-035).
 *
 * <p>Audit's counterpart to Reporting's {@code ReportExport}, with two differences that matter:
 *
 * <ul>
 *   <li><b>It is not owned by a merchant.</b> A report export belongs to the merchant who asked; an
 *       audit export is a privileged read requested by platform staff. It carries {@code requestedBy}
 *       (the operator) and an optional {@code merchantFilter} that narrows the window to one tenant.
 *   <li><b>The request is itself auditable.</b> {@code requestedBy} is never null: exporting the
 *       audit log is a privileged action, and who ran it is a fact worth keeping.
 * </ul>
 *
 * Immutable through intent methods ({@link #complete}, {@link #fail}), never setters -- the same
 * shape {@code ReportExport} and {@code Notification} have.
 */
public final class AuditExport {

    private final AuditExportId id;
    private final String requestedBy;
    private final MerchantId merchantFilter;
    private final AuditWindow window;
    private final AuditExportStatus status;
    private final Integer rowCount;
    private final String content;
    private final String failureReason;
    private final Instant requestedAt;
    private final Instant completedAt;

    private AuditExport(
        AuditExportId id,
        String requestedBy,
        MerchantId merchantFilter,
        AuditWindow window,
        AuditExportStatus status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        this.id = id;
        this.requestedBy = requestedBy;
        this.merchantFilter = merchantFilter;
        this.window = window;
        this.status = status;
        this.rowCount = rowCount;
        this.content = content;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    /** A fresh, unrendered export. */
    public static AuditExport request(
        AuditExportId id,
        String requestedBy,
        MerchantId merchantFilter,
        AuditWindow window,
        Instant now
    ) {
        if (id == null || window == null || now == null) {
            throw new IllegalArgumentException("An audit export needs an id, a window and a time");
        }

        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("An audit export must record who requested it");
        }

        return new AuditExport(
            id, requestedBy, merchantFilter, window, AuditExportStatus.PENDING,
            null, null, null, now, null
        );
    }

    /** Rehydrates a row. No validation: the row was valid when it was written. */
    public static AuditExport reconstitute(
        AuditExportId id,
        String requestedBy,
        MerchantId merchantFilter,
        AuditWindow window,
        AuditExportStatus status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        return new AuditExport(
            id, requestedBy, merchantFilter, window, status,
            rowCount, content, failureReason, requestedAt, completedAt
        );
    }

    /**
     * The CSV is rendered.
     *
     * @throws IllegalArgumentException on a null body -- {@code ck_audit_exports_completed} would
     *     refuse the row anyway, and a COMPLETED export with no file is a 200 for a download that did
     *     not happen
     */
    public AuditExport complete(String csv, int rows, Instant now) {
        if (csv == null) {
            throw new IllegalArgumentException("A completed export must carry its CSV");
        }

        if (rows < 0) {
            throw new IllegalArgumentException("A completed export cannot have " + rows + " rows");
        }

        return new AuditExport(
            id, requestedBy, merchantFilter, window, AuditExportStatus.COMPLETED,
            rows, csv, null, requestedAt, now
        );
    }

    /** Terminal. Keeps requestedAt and takes no completedAt: nothing completed. */
    public AuditExport fail(String reason) {
        return new AuditExport(
            id, requestedBy, merchantFilter, window, AuditExportStatus.FAILED,
            null, null, reason, requestedAt, null
        );
    }

    public AuditExportId id() {
        return id;
    }

    public String requestedBy() {
        return requestedBy;
    }

    public MerchantId merchantFilter() {
        return merchantFilter;
    }

    public AuditWindow window() {
        return window;
    }

    public AuditExportStatus status() {
        return status;
    }

    public Integer rowCount() {
        return rowCount;
    }

    public String content() {
        return content;
    }

    public String failureReason() {
        return failureReason;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }
}
