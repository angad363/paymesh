package com.paymesh.settlement.infrastructure.events;

import com.paymesh.settlement.application.RevertRejectedBatchService;
import com.paymesh.settlement.domain.SettlementBatchId;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

/** Settlement's subscription to the Ledger's {@code settlement.batch_rejected} (ADR-047 §4). */
public final class BatchRejectedHandler implements EventHandler {

    private final RevertRejectedBatchService revert;

    public BatchRejectedHandler(RevertRejectedBatchService revert) {
        this.revert = revert;
    }

    @Override
    public String consumerName() {
        return "settlement.batch-rejected";
    }

    @Override
    public String eventType() {
        return "settlement.batch_rejected";
    }

    @Override
    public void handle(OutboxEvent event) {
        Object batchId = event.payload().get("settlementBatchId");

        if (batchId == null || batchId.toString().isBlank()) {
            throw new IllegalArgumentException("settlement.batch_rejected carries no settlementBatchId");
        }

        Object reason = event.payload().get("reason");

        revert.revert(
            event.merchantId(),
            SettlementBatchId.from(batchId.toString()),
            reason == null ? "unspecified" : reason.toString()
        );
    }
}
