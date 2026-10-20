package com.paymesh.settlement.infrastructure.events;

import com.paymesh.settlement.application.MarkBatchPostedService;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/** Settlement's subscription to the Ledger's {@code ledger.batch_posted} (ADR-047 section 8). */
public final class BatchPostedHandler implements EventHandler {

    private final MarkBatchPostedService markPosted;

    public BatchPostedHandler(MarkBatchPostedService markPosted) {
        this.markPosted = markPosted;
    }

    @Override
    public String consumerName() {
        return "settlement-ledger-batch-posted";
    }

    @Override
    public String eventType() {
        return "ledger.batch_posted";
    }

    @Override
    public void handle(OutboxEvent event) {
        Object batchId = event.payload().get("settlementBatchId");

        if (batchId == null || batchId.toString().isBlank()) {
            throw new IllegalArgumentException("ledger.batch_posted carries no settlementBatchId");
        }

        markPosted.markPosted(event.merchantId(), SettlementBatchId.from(batchId.toString()));
    }
}
