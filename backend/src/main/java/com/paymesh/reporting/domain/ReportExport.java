package com.paymesh.reporting.domain;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;

/**
 * A merchant's request for a CSV of their facts, and the file once it exists.
 *
 * <p>Immutable through intent methods, never setters: {@link #complete} and {@link #fail} return a
 * new instance, so an export can be read and reasoned about without a hidden write in between --
 * the same shape {@code Notification} has.
 *
 * <h2>The CSV is carried here, and the ceiling is deliberate</h2>
 *
 * There is no object storage in this project, so the content is a TEXT column (V35's header says
 * why at length). It will not hold a million-row export. What it will not do is hand a merchant a
 * download URL for a bucket that does not exist.
 */
public final class ReportExport {

    private final ReportExportId id;
    private final MerchantId merchantId;
    private final ReportWindow window;
    private final ReportExportStatus status;
    private final Integer rowCount;
    private final String content;
    private final String failureReason;
    private final Instant requestedAt;
    private final Instant completedAt;

    private ReportExport(
        ReportExportId id,
        MerchantId merchantId,
        ReportWindow window,
        ReportExportStatus status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        this.id = id;
        this.merchantId = merchantId;
        this.window = window;
        this.status = status;
        this.rowCount = rowCount;
        this.content = content;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    /** A fresh, unrendered export. */
    public static ReportExport request(
        ReportExportId id,
        MerchantId merchantId,
        ReportWindow window,
        Instant now
    ) {
        if (id == null || merchantId == null || window == null || now == null) {
            throw new IllegalArgumentException("A report export needs an id, a merchant and a window");
        }

        return new ReportExport(
            id, merchantId, window, ReportExportStatus.PENDING, null, null, null, now, null
        );
    }

    /** Rehydrates a row. No validation: the row was valid when it was written. */
    public static ReportExport reconstitute(
        ReportExportId id,
        MerchantId merchantId,
        ReportWindow window,
        ReportExportStatus status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        return new ReportExport(
            id, merchantId, window, status, rowCount, content, failureReason, requestedAt, completedAt
        );
    }

    /**
     * The CSV is rendered. Carries the count separately from the content because a merchant polling
     * the status wants to know how much they are about to download without downloading it.
     *
     * @throws IllegalArgumentException on a null body -- {@code ck_report_exports_completed} would
     *     refuse the row anyway, and a COMPLETED export with no file is a 200 for a download that
     *     did not happen
     */
    public ReportExport complete(String csv, int rows, Instant now) {
        if (csv == null) {
            throw new IllegalArgumentException("A completed export must carry its CSV");
        }

        if (rows < 0) {
            throw new IllegalArgumentException("A completed export cannot have " + rows + " rows");
        }

        return new ReportExport(
            id, merchantId, window, ReportExportStatus.COMPLETED, rows, csv, null, requestedAt, now
        );
    }

    /** Terminal. Keeps requestedAt and takes no completedAt: nothing completed. */
    public ReportExport fail(String reason) {
        return new ReportExport(
            id, merchantId, window, ReportExportStatus.FAILED, null, null, reason, requestedAt, null
        );
    }

    public ReportExportId id() {
        return id;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public ReportWindow window() {
        return window;
    }

    public ReportExportStatus status() {
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
