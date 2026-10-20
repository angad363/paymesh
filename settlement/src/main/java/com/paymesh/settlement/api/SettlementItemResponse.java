package com.paymesh.settlement.api;

import com.paymesh.settlement.domain.SettlementItem;

/**
 * One line of a statement.
 *
 * @param amountMinor signed, and negative is real: a payment refunded after its funds were released
 *     comes off this batch. A merchant reconciling a statement needs to see that line, not a total
 *     that quietly absorbed it
 */
public record SettlementItemResponse(
    String settlementItemId, String paymentIntentId, long amountMinor
) {

    public static SettlementItemResponse from(SettlementItem item) {
        return new SettlementItemResponse(
            item.settlementItemId(), item.paymentIntentId(), item.amountMinor()
        );
    }
}
