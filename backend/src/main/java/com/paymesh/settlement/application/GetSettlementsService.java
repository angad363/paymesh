package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;

/** The merchant's read side: their statements, and one of them in full. */
public final class GetSettlementsService {

    /** Newest first, and capped. No cursor: no merchant has enough batches to page through yet. */
    private static final int MAX_PAGE = 100;

    private final SettlementBatchRepository batches;

    public GetSettlementsService(SettlementBatchRepository batches) {
        this.batches = batches;
    }

    public List<SettlementBatch> list(MerchantId merchantId) {
        return batches.listByMerchant(merchantId, MAX_PAGE);
    }

    /**
     * @throws SettlementBatchNotFoundException for another merchant's batch as well as for one that
     *     does not exist. The two answers are identical on purpose -- distinguishing them makes the
     *     endpoint an oracle for enumerating another tenant's settlement ids
     */
    public SettlementBatch get(MerchantId merchantId, SettlementBatchId settlementBatchId) {
        return batches.find(merchantId, settlementBatchId)
            .orElseThrow(() -> new SettlementBatchNotFoundException(settlementBatchId));
    }
}
