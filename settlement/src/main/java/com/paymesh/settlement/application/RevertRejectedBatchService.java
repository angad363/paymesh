package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.PayoutStatus;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.settlement.domain.SettlementBatchStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * Reverts a cut the Ledger refused (ADR-047 §4, option A): {@code settlement.batch_rejected}.
 *
 * <h2>NOTHING WAS POSTED, SO NOTHING IS REVERSED IN THE LEDGER</h2>
 *
 * The Ledger posts nothing for a rejected batch. The revert is therefore Settlement's own: the
 * payout fails and the batch is marked RETURNED (a status change the append-only trigger permits),
 * which frees its items from {@code batchedAmounts} so the next cut re-itemises them from a fresh
 * read. It deliberately does NOT emit {@code payout.returned}: that event makes the Ledger move
 * in-transit back to available, and there is no in-transit to move.
 *
 * <h2>Only a payout that has not left the building is reverted</h2>
 *
 * A payout already SUBMITTED may be paid by the provider; reverting would strand real cash. That
 * case is logged at ERROR for an operator and left alone.
 *
 * <p>Assumes a transaction is open (the event dispatcher's) and must not start one.
 */
public final class RevertRejectedBatchService {

    private static final Logger log = LoggerFactory.getLogger(RevertRejectedBatchService.class);

    private final SettlementBatchRepository batches;
    private final PayoutRepository payouts;
    private final Clock clock;

    public RevertRejectedBatchService(
        SettlementBatchRepository batches, PayoutRepository payouts, Clock clock
    ) {
        this.batches = batches;
        this.payouts = payouts;
        this.clock = clock;
    }

    public void revert(MerchantId merchantId, SettlementBatchId settlementBatchId, String reason) {
        SettlementBatch batch = batches.find(merchantId, settlementBatchId).orElse(null);

        if (batch != null && batch.status() == SettlementBatchStatus.RETURNED) {
            // ALREADY REVERTED (a duplicate delivery): handled, so a quiet no-op -- not an error.
            log.info(
                "Rejected batch already reverted, nothing to do settlementBatchId={}",
                settlementBatchId.value()
            );
            return;
        }

        if (batch == null || batch.status() != SettlementBatchStatus.PENDING_PAYOUT) {
            log.info(
                "Rejected batch needs no revert settlementBatchId={} status={}",
                settlementBatchId.value(), batch == null ? "absent" : batch.status()
            );
            return;
        }

        Payout payout = payouts.findByBatch(settlementBatchId)
            .flatMap(found -> payouts.findForUpdate(found.payoutId()))
            .orElse(null);

        if (payout != null && payout.status() != PayoutStatus.PENDING) {
            log.error(
                "Ledger rejected settlementBatchId={} but its payout is already {}; NOT reverted,"
                    + " an operator must reconcile", settlementBatchId.value(), payout.status()
            );
            return;
        }

        Instant now = Instant.now(clock);

        if (payout != null) {
            payouts.save(payout.failed("Ledger rejected the cut: " + reason, now));
        }

        batches.updateStatus(batch.markReturned(now));

        log.warn(
            "Reverted a cut the ledger rejected settlementBatchId={} merchantId={} reason={}",
            settlementBatchId.value(), merchantId.value(), reason
        );
    }
}
