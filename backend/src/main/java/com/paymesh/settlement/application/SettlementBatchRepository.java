package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

public interface SettlementBatchRepository {

    /** Header and items, in one transaction. The deferred trigger checks the pair at COMMIT. */
    SettlementBatch save(SettlementBatch batch);

    /** Status only. The header is immutable otherwise, and a trigger says so, not a comment. */
    SettlementBatch updateStatus(SettlementBatch batch);

    /** Merchant-scoped: another tenant's batch is NOT FOUND rather than forbidden. */
    Optional<SettlementBatch> find(MerchantId merchantId, SettlementBatchId settlementBatchId);

    /** Newest first. Capped by the caller; there is no cursor here yet and no merchant has asked. */
    List<SettlementBatch> listByMerchant(MerchantId merchantId, int limit);

    /**
     * How much of each payment this merchant has already committed to a batch, by currency.
     * <p>
     * RETURNED batches are excluded: their funds went back to available through a new journal, so
     * counting them would net the same money off twice and shrink every later batch by the amount
     * of a payout that failed.
     */
    List<BatchedAmount> batchedAmounts(MerchantId merchantId);

    /** @param amountMinor signed, summed across every non-returned batch */
    record BatchedAmount(String currency, String paymentIntentId, long amountMinor) {
    }
}
