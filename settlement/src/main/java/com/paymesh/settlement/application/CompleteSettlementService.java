package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.Payout;
import com.paymesh.settlement.domain.SettlementBatch;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * How a settlement ends: paid, or returned. SDD 17.6 invariant 3, ADR-032.
 *
 * <h2>ONE PLACE, BECAUSE THERE ARE TWO WAYS IN AND THEY MUST AGREE</h2>
 *
 * A payout ends because the provider answered, or because it ran out of attempts without one. Those
 * arrive through different services and both have to do the same three things -- move the payout,
 * move the batch, and emit the event the Ledger posts from. Written twice they would drift, and the
 * drift is not a compile error: it is a batch marked RETURNED whose funds were never returned.
 *
 * <h2>Assumes a transaction is open, and must not start one</h2>
 *
 * Same contract as {@link OutboxWriter} for the same reason: the row change and the event that
 * announces it commit together or not at all (ADR-010). Both callers wrap this in their own
 * {@code TransactionTemplate}, visibly.
 */
public final class CompleteSettlementService {

    private static final Logger log = LoggerFactory.getLogger(CompleteSettlementService.class);

    private static final int EVENT_VERSION = 1;

    private final SettlementBatchRepository batches;
    private final PayoutRepository payouts;
    private final OutboxWriter outbox;
    private final Clock clock;

    public CompleteSettlementService(
        SettlementBatchRepository batches,
        PayoutRepository payouts,
        OutboxWriter outbox,
        Clock clock
    ) {
        this.batches = batches;
        this.payouts = payouts;
        this.outbox = outbox;
        this.clock = clock;
    }

    /** The provider confirmed. In-transit is discharged against PayMesh's cash. */
    public void markPaid(Payout payout, SettlementBatch batch) {
        Instant now = Instant.now(clock);

        payouts.save(payout.paid(now));
        batches.updateStatus(batch.markPaid(now));
        outbox.append(event("payout.paid", batch, now));

        log.info(
            "Payout {} paid settlementBatchId={} amount={} {}",
            payout.payoutId().value(), batch.settlementBatchId().value(),
            batch.netAmountMinor(), batch.currency()
        );
    }

    /**
     * The payout is not going to happen. The money goes back to available, by a NEW journal.
     *
     * @param reason the provider's words, or the budget running out. Kept on the payout so an
     *     operator asking "why is this merchant not being paid?" has the answer on the row rather
     *     than in a log nobody kept
     */
    public void returnFunds(Payout payout, SettlementBatch batch, String reason) {
        Instant now = Instant.now(clock);

        // Already FAILED when the budget ran out during submission; failed() would refuse a second
        // terminal transition, which is correct and is why this asks first rather than assuming.
        payouts.save(payout.isTerminal() ? payout : payout.failed(reason, now));
        batches.updateStatus(batch.markReturned(now));
        outbox.append(event("payout.returned", batch, now));

        log.warn(
            "Payout {} returned settlementBatchId={} amount={} {} reason={}",
            payout.payoutId().value(), batch.settlementBatchId().value(),
            batch.netAmountMinor(), batch.currency(), reason
        );
    }

    /**
     * Both events carry the BATCH, not the payout, because the Ledger posts against the batch --
     * its journals are keyed on {@code settlementBatchId} and a payout is Settlement's own
     * bookkeeping. The payout id rides along for tracing and nothing reads it.
     */
    private static OutboxEvent event(String eventType, SettlementBatch batch, Instant now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("settlementBatchId", batch.settlementBatchId().value());
        payload.put("merchantId", batch.merchantId().value());
        payload.put("amountMinor", batch.netAmountMinor());
        payload.put("currency", batch.currency());
        payload.put("occurredAt", now.toString());

        return new OutboxEvent(
            EventId.generate(),
            batch.merchantId(),
            "SETTLEMENT_BATCH",
            batch.settlementBatchId().value(),
            eventType,
            EVENT_VERSION,
            payload,
            now
        );
    }
}
