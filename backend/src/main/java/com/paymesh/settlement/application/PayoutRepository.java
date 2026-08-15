package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutId;
import com.paymesh.settlement.domain.SettlementBatchId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PayoutRepository {

    Payout save(Payout payout);

    Optional<Payout> find(PayoutId payoutId);

    Optional<Payout> findByBatch(SettlementBatchId settlementBatchId);

    /**
     * Payout IDS that are due and not terminal, oldest first.
     * <p>
     * IDS, not aggregates, and for the reason open item 2 cost this codebase five sweeps: a row the
     * mapper chokes on must cost one payout rather than the pass, so nothing is mapped before the
     * per-item boundary.
     */
    List<String> findDue(Instant now, int limit);

    /** Under a row lock, so two sweeps cannot submit one payout twice. */
    Optional<Payout> findForUpdate(PayoutId payoutId);
}
