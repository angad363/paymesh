package com.paymesh.settlement.domain;

/** A batch already answered for -- paid or returned -- being answered for again. */
public final class SettlementBatchNotPendingException extends RuntimeException {

    private final SettlementBatchId settlementBatchId;
    private final SettlementBatchStatus status;

    public SettlementBatchNotPendingException(
        SettlementBatchId settlementBatchId, SettlementBatchStatus status
    ) {
        super("Settlement batch " + settlementBatchId.value() + " is already " + status);

        this.settlementBatchId = settlementBatchId;
        this.status = status;
    }

    public SettlementBatchId settlementBatchId() {
        return settlementBatchId;
    }

    public SettlementBatchStatus status() {
        return status;
    }
}
