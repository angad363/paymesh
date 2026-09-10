package com.paymesh.reporting.infrastructure.persistence.jpa;

import com.paymesh.reporting.application.FactTally;
import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportExportStatus;
import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.sql.Date;
import java.time.LocalDate;

/** Between the domain types and their rows. Both directions, both tables, one place. */
final class ReportJpaMapper {

    private ReportJpaMapper() {
    }

    static ReportFactJpaEntity toEntity(ReportFact fact) {
        return new ReportFactJpaEntity(
            fact.sourceEventId(),
            fact.merchantId().value(),
            fact.eventType(),
            fact.subjectId(),
            fact.orderId(),
            fact.currency(),
            fact.amountMinor(),
            fact.occurredAt(),
            fact.recordedAt()
        );
    }

    static ReportFact toDomain(ReportFactJpaEntity entity) {
        return new ReportFact(
            entity.getSourceEventId(),
            MerchantId.from(entity.getMerchantId()),
            entity.getEventType(),
            entity.getSubjectId(),
            entity.getOrderId(),
            entity.getCurrency(),
            entity.getAmountMinor(),
            entity.getOccurredAt(),
            entity.getRecordedAt()
        );
    }

    /**
     * One row of the native tally query, whose column ORDER is the contract:
     * currency, day, event type, count, summed amount.
     *
     * <p>The numeric columns arrive as some {@link Number} -- PostgreSQL's {@code count(*)} is a
     * {@code BIGINT} and its {@code sum()} over a {@code BIGINT} is a {@code NUMERIC}, which the
     * driver hands back as {@code BigDecimal}. Reading them through {@code Number} rather than
     * casting to a specific type is what stops a perfectly correct query throwing a
     * {@code ClassCastException} at runtime.
     *
     * <p>The day arrives as a {@link Date} because the query casts to {@code ::date}.
     */
    static FactTally toTally(Object[] row) {
        return new FactTally(
            (String) row[0],
            toLocalDate(row[1]),
            (String) row[2],
            ((Number) row[3]).longValue(),
            ((Number) row[4]).longValue()
        );
    }

    private static LocalDate toLocalDate(Object value) {
        return value instanceof LocalDate date ? date : ((Date) value).toLocalDate();
    }

    static ReportExportJpaEntity toEntity(ReportExport export) {
        return new ReportExportJpaEntity(
            export.id().value(),
            export.merchantId().value(),
            export.window().from(),
            export.window().to(),
            export.status().name(),
            export.rowCount(),
            export.content(),
            export.failureReason(),
            export.requestedAt(),
            export.completedAt()
        );
    }

    static ReportExport toDomain(ReportExportJpaEntity entity) {
        return ReportExport.reconstitute(
            ReportExportId.from(entity.getReportExportId()),
            MerchantId.from(entity.getMerchantId()),
            new ReportWindow(entity.getWindowFrom(), entity.getWindowTo()),
            ReportExportStatus.valueOf(entity.getStatus()),
            entity.getRowCount(),
            entity.getContent(),
            entity.getFailureReason(),
            entity.getRequestedAt(),
            entity.getCompletedAt()
        );
    }
}
