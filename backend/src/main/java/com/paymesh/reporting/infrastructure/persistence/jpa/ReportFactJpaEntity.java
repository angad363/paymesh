package com.paymesh.reporting.infrastructure.persistence.jpa;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A row of {@code report_facts}.
 *
 * <p>Every column is {@code updatable = false}: the table is append-only, and saying so here means
 * a stray {@code save} of a modified instance is a no-op rather than a rewrite of history.
 */
@Entity
@Table(name = "report_facts")
public class ReportFactJpaEntity {

    @Id
    @Column(name = "source_event_id", nullable = false, updatable = false, length = 40)
    private String sourceEventId;

    @Column(name = "merchant_id", nullable = false, updatable = false, length = 40)
    private String merchantId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "subject_id", nullable = false, updatable = false, length = 64)
    private String subjectId;

    @Column(name = "order_id", updatable = false, length = 40)
    private String orderId;

    // CHAR(3) in the migration, so the JDBC type has to say CHAR. Without this, schema validation
    // fails the whole context on a bpchar-vs-varchar mismatch -- which reds every integration test
    // in the suite rather than just this capability's.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    protected ReportFactJpaEntity() {
    }

    public ReportFactJpaEntity(
        String sourceEventId,
        String merchantId,
        String eventType,
        String subjectId,
        String orderId,
        String currency,
        long amountMinor,
        Instant occurredAt,
        Instant recordedAt
    ) {
        this.sourceEventId = sourceEventId;
        this.merchantId = merchantId;
        this.eventType = eventType;
        this.subjectId = subjectId;
        this.orderId = orderId;
        this.currency = currency;
        this.amountMinor = amountMinor;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSubjectId() {
        return subjectId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
