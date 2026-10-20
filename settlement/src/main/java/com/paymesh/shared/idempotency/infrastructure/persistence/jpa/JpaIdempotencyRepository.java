package com.paymesh.shared.idempotency.infrastructure.persistence.jpa;

import com.paymesh.shared.idempotency.application.IdempotencyRepository;
import com.paymesh.shared.idempotency.domain.IdempotencyRecord;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the IdempotencyRepository port.
 * Everything JPA stays on this side of the interface; the filter sees only domain records.
 */
public final class JpaIdempotencyRepository implements IdempotencyRepository {

    private final SpringDataIdempotencyRepository records;

    public JpaIdempotencyRepository(SpringDataIdempotencyRepository records) {
        this.records = records;
    }

    @Override
    public boolean insertIfAbsent(IdempotencyRecord record) {
        // One statement, no preceding read. The row count is the database's answer to "did you win
        // the race?" -- 1 means this caller owns the request, 0 means somebody else already does.
        // Checking first and trusting the check is the bug: under concurrent retries every caller
        // reads "absent" and every caller goes on to run the handler.
        return records.insertIfAbsent(
            record.merchantId().value(),
            record.endpoint(),
            record.idempotencyKey(),
            record.requestHash(),
            record.createdAt()
        ) == 1;
    }

    @Override
    public Optional<IdempotencyRecord> findBy(
        MerchantId merchantId,
        String endpoint,
        String idempotencyKey
    ) {
        return records
            .findById(new IdempotencyRecordJpaId(merchantId.value(), endpoint, idempotencyKey))
            .map(IdempotencyRecordJpaMapper::toDomain);
    }

    @Override
    public void complete(IdempotencyRecord record) {
        records.complete(
            record.merchantId().value(),
            record.endpoint(),
            record.idempotencyKey(),
            record.responseStatus().shortValue(),
            record.responseBody(),
            record.completedAt()
        );
    }

    @Override
    public void delete(MerchantId merchantId, String endpoint, String idempotencyKey) {
        records.deleteRecord(merchantId.value(), endpoint, idempotencyKey);
    }
}
