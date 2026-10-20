package com.paymesh.settlement.infrastructure.persistence.jpa;

import com.paymesh.settlement.application.SettlementBatchRepository;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.settlement.domain.SettlementBatchStatus;
import com.paymesh.settlement.domain.SettlementItem;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

public final class JpaSettlementBatchRepository implements SettlementBatchRepository {

    private final SpringDataSettlementBatchRepository batches;
    private final SpringDataSettlementItemRepository items;

    public JpaSettlementBatchRepository(
        SpringDataSettlementBatchRepository batches, SpringDataSettlementItemRepository items
    ) {
        this.batches = batches;
        this.items = items;
    }

    /**
     * Header first, flushed, then the items.
     *
     * <p>The order is the ledger's, for the ledger's reason: the items' composite foreign keys
     * point at the header, so it has to exist first. {@code tr_settlement_batches_total} is
     * DEFERRED and runs at COMMIT, which is the only moment a batch is a complete object -- so an
     * arithmetic error surfaces from the commit rather than from here.
     */
    @Override
    public SettlementBatch save(SettlementBatch batch) {
        batches.saveAndFlush(new SettlementBatchJpaEntity(
            batch.settlementBatchId().value(),
            batch.merchantId().value(),
            batch.currency(),
            batch.netAmountMinor(),
            batch.status().name(),
            batch.cutAt(),
            batch.createdAt(),
            batch.updatedAt()
        ));

        for (SettlementItem item : batch.items()) {
            items.save(new SettlementItemJpaEntity(
                item.settlementItemId(),
                batch.settlementBatchId().value(),
                batch.merchantId().value(),
                batch.currency(),
                item.paymentIntentId(),
                item.amountMinor(),
                batch.createdAt()
            ));
        }

        items.flush();

        return batch;
    }

    /**
     * Status and {@code updated_at} only.
     * <p>
     * Loaded and mutated rather than reconstructed, so a field this method has no business touching
     * cannot be written by accident. {@code tr_settlement_batches_append_only} is the actual guard
     * and would refuse the write; this is what keeps it from ever having to.
     */
    @Override
    public SettlementBatch updateStatus(SettlementBatch batch) {
        SettlementBatchJpaEntity entity = batches.findById(batch.settlementBatchId().value())
            .orElseThrow(() -> new IllegalStateException(
                "Settlement batch " + batch.settlementBatchId().value() + " vanished"
            ));

        entity.applyStatus(batch.status().name(), batch.updatedAt());
        batches.saveAndFlush(entity);

        return batch;
    }

    @Override
    public Optional<SettlementBatch> find(
        MerchantId merchantId, SettlementBatchId settlementBatchId
    ) {
        return batches
            .findBySettlementBatchIdAndMerchantId(settlementBatchId.value(), merchantId.value())
            .map(this::toDomain);
    }

    @Override
    public List<SettlementBatch> listByMerchant(MerchantId merchantId, int limit) {
        return batches
            .findByMerchantIdOrderByCutAtDescSettlementBatchIdDesc(
                merchantId.value(), Limit.of(limit)
            )
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    public List<BatchedAmount> batchedAmounts(MerchantId merchantId) {
        return batches.batchedAmounts(merchantId.value()).stream()
            .map(row -> new BatchedAmount((String) row[0], (String) row[1], (Long) row[2]))
            .toList();
    }

    private SettlementBatch toDomain(SettlementBatchJpaEntity entity) {
        List<SettlementItem> lines = items
            .findBySettlementBatchIdOrderBySettlementItemIdAsc(entity.settlementBatchId())
            .stream()
            .map(item -> new SettlementItem(
                item.settlementItemId(), item.paymentIntentId(), item.amountMinor()
            ))
            .toList();

        return new SettlementBatch(
            SettlementBatchId.from(entity.settlementBatchId()),
            MerchantId.from(entity.merchantId()),
            entity.currency(),
            entity.netAmountMinor(),
            SettlementBatchStatus.valueOf(entity.status()),
            lines,
            entity.cutAt(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
