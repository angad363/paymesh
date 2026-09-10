package com.paymesh.reporting.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row of {@code report_exports}. */
@Entity
@Table(name = "report_exports")
public class ReportExportJpaEntity {

    @Id
    @Column(name = "report_export_id", nullable = false, updatable = false, length = 40)
    private String reportExportId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "window_from", nullable = false, updatable = false)
    private Instant windowFrom;

    @Column(name = "window_to", nullable = false, updatable = false)
    private Instant windowTo;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "row_count")
    private Integer rowCount;

    @Column(name = "content")
    private String content;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected ReportExportJpaEntity() {
    }

    public ReportExportJpaEntity(
        String reportExportId,
        String merchantId,
        Instant windowFrom,
        Instant windowTo,
        String status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        this.reportExportId = reportExportId;
        this.merchantId = merchantId;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.status = status;
        this.rowCount = rowCount;
        this.content = content;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    public String getReportExportId() {
        return reportExportId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public Instant getWindowFrom() {
        return windowFrom;
    }

    public Instant getWindowTo() {
        return windowTo;
    }

    public String getStatus() {
        return status;
    }

    public Integer getRowCount() {
        return rowCount;
    }

    public String getContent() {
        return content;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
