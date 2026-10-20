package com.paymesh.settlement.api;

import com.paymesh.settlement.domain.SettlementBatch;

import java.time.Instant;
import java.util.List;

/**
 * A settlement, as a merchant sees it. SDD 17.3.
 *
 * @param netAmountMinor what this batch pays. Integer minor units, like every other amount in this
 *     API. There is no gross and no fees figure because there is no fee schedule -- reporting a
 *     gross equal to the net and a fee of zero would claim a deduction was calculated
 * @param items the payments this batch is made of, so the figure can be reconciled against orders
 *     rather than taken on trust
 */
public record SettlementBatchResponse(
    String settlementBatchId,
    String currency,
    long netAmountMinor,
    String status,
    Instant cutAt,
    List<SettlementItemResponse> items
) {

    public static SettlementBatchResponse from(SettlementBatch batch) {
        return new SettlementBatchResponse(
            batch.settlementBatchId().value(),
            batch.currency(),
            batch.netAmountMinor(),
            batch.status().name(),
            batch.cutAt(),
            batch.items().stream().map(SettlementItemResponse::from).toList()
        );
    }
}
