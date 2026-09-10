package com.paymesh.audit.infrastructure.persistence.jpa;

import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditEventId;
import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;
import com.paymesh.audit.domain.AuditExportStatus;
import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.audit.ActorType;
import com.paymesh.shared.tenant.MerchantId;

/** Between the domain types and their rows. Both directions, both tables, one place. */
final class AuditJpaMapper {

    private AuditJpaMapper() {
    }

    static AuditEventJpaEntity toEntity(AuditEvent event) {
        return new AuditEventJpaEntity(
            event.id().value(),
            event.actorType().name(),
            event.actorId(),
            event.merchantId() == null ? null : event.merchantId().value(),
            event.action(),
            event.resourceType(),
            event.resourceId(),
            event.reason(),
            event.beforeHash(),
            event.afterHash(),
            event.ipHash(),
            event.occurredAt()
        );
    }

    static AuditEvent toDomain(AuditEventJpaEntity entity) {
        return AuditEvent.reconstitute(
            AuditEventId.from(entity.getAuditEventId()),
            ActorType.valueOf(entity.getActorType()),
            entity.getActorId(),
            entity.getMerchantId() == null ? null : MerchantId.from(entity.getMerchantId()),
            entity.getAction(),
            entity.getResourceType(),
            entity.getResourceId(),
            entity.getReason(),
            entity.getBeforeHash(),
            entity.getAfterHash(),
            entity.getIpHash(),
            entity.getOccurredAt()
        );
    }

    static AuditExportJpaEntity toEntity(AuditExport export) {
        return new AuditExportJpaEntity(
            export.id().value(),
            export.requestedBy(),
            export.merchantFilter() == null ? null : export.merchantFilter().value(),
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

    static AuditExport toDomain(AuditExportJpaEntity entity) {
        return AuditExport.reconstitute(
            AuditExportId.from(entity.getAuditExportId()),
            entity.getRequestedBy(),
            entity.getMerchantFilter() == null ? null : MerchantId.from(entity.getMerchantFilter()),
            new AuditWindow(entity.getWindowFrom(), entity.getWindowTo()),
            AuditExportStatus.valueOf(entity.getStatus()),
            entity.getRowCount(),
            entity.getContent(),
            entity.getFailureReason(),
            entity.getRequestedAt(),
            entity.getCompletedAt()
        );
    }
}
