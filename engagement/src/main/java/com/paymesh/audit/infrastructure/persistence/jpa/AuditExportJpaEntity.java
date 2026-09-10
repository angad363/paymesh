package com.paymesh.audit.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A row of {@code audit_exports}. */
@Entity
@Table(name = "audit_exports")
public class AuditExportJpaEntity {

    @Id
    @Column(name = "audit_export_id", nullable = false, updatable = false, length = 40)
    private String auditExportId;

    @Column(name = "requested_by", nullable = false, updatable = false, length = 64)
    private String requestedBy;

    @Column(name = "merchant_filter", updatable = false, length = 40)
    private String merchantFilter;

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

    protected AuditExportJpaEntity() {
    }

    public AuditExportJpaEntity(
        String auditExportId,
        String requestedBy,
        String merchantFilter,
        Instant windowFrom,
        Instant windowTo,
        String status,
        Integer rowCount,
        String content,
        String failureReason,
        Instant requestedAt,
        Instant completedAt
    ) {
        this.auditExportId = auditExportId;
        this.requestedBy = requestedBy;
        this.merchantFilter = merchantFilter;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.status = status;
        this.rowCount = rowCount;
        this.content = content;
        this.failureReason = failureReason;
        this.requestedAt = requestedAt;
        this.completedAt = completedAt;
    }

    public String getAuditExportId() {
        return auditExportId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getMerchantFilter() {
        return merchantFilter;
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
