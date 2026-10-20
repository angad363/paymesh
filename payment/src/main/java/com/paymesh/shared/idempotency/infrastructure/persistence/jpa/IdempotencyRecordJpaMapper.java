package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.idempotency.domain.IdempotencyStatus;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Row to domain record. One direction only: rows are written by the single statements in
 * {@link JpaIdempotencyRepository}, never by persisting this entity, because the database has to be
 * the thing that decides who wins the insert.
 */
final class IdempotencyRecordJpaMapper {

    private IdempotencyRecordJpaMapper() {
    }

    static IdempotencyRecord toDomain(IdempotencyRecordJpaEntity entity) {
        return new IdempotencyRecord(
            MerchantId.from(entity.id().merchantId()),
            entity.id().endpoint(),
            entity.id().idempotencyKey(),
            entity.requestHash(),
            IdempotencyStatus.valueOf(entity.status()),
            entity.responseStatus() == null ? null : entity.responseStatus().intValue(),
            entity.responseBody(),
            entity.createdAt(),
            entity.completedAt()
        );
    }
}
