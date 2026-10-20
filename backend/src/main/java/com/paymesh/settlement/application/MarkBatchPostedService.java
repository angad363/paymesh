package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.settlement.domain.SettlementBatchStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;

/**
 * Applies the Ledger's {@code ledger.batch_posted} (ADR-047 section 8): the cut is journalled, so
 * its payout may now be submitted.
 *
 * <p>Idempotent: a batch already POSTED, PAID or RETURNED is left alone (a duplicate delivery, or a
 * posted event that lost the race with nothing -- a rejected batch is never posted). Assumes the
 * event dispatcher's transaction; must not start one.
 */
public final class MarkBatchPostedService {

    private static final Logger log = LoggerFactory.getLogger(MarkBatchPostedService.class);

    private final SettlementBatchRepository batches;
    private final Clock clock;

    public MarkBatchPostedService(SettlementBatchRepository batches, Clock clock) {
        this.batches = batches;
        this.clock = clock;
    }

    public void markPosted(MerchantId merchantId, SettlementBatchId settlementBatchId) {
        SettlementBatch batch = batches.find(merchantId, settlementBatchId).orElse(null);

        if (batch != null && batch.status() == SettlementBatchStatus.RETURNED) {
            // Not retried: the ledger posted money settlement already returned. Logged loudly for an
            // operator to reconcile, rather than thrown into an infinite inbox retry.
            log.error(
                "Ledger posted a batch settlement already RETURNED, NOT updated, reconcile settlementBatchId={}",
                settlementBatchId.value()
            );
            return;
        }

        if (batch == null || batch.status() != SettlementBatchStatus.PENDING_PAYOUT) {
            log.info(
                "Posted batch needs no update settlementBatchId={} status={}",
                settlementBatchId.value(), batch == null ? "absent" : batch.status()
            );
            return;
        }

        batches.updateStatus(batch.markPosted(Instant.now(clock)));
    }
}
