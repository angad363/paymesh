package com.paymesh.settlement.application;

import com.paymesh.settlement.domain.SettlementBatchId;

/** No such batch, or not this merchant's. Deliberately one exception for both. */
public final class SettlementBatchNotFoundException extends RuntimeException {

    public SettlementBatchNotFoundException(SettlementBatchId settlementBatchId) {
        super("Settlement batch " + settlementBatchId.value() + " was not found");
    }
}
