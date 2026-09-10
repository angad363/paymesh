package com.paymesh.reporting.application;

import java.time.LocalDate;
import java.util.List;

/**
 * SDD 19.2's "success/failure totals and trends", for ONE currency.
 *
 * <h2>PER CURRENCY, AND NOTHING HERE EVER ADDS TWO OF THEM</h2>
 *
 * A merchant collecting in USD and EUR gets two of these, not one with a meaningless sum. Money in
 * this codebase is always integer minor units plus an explicit currency, and a report is the
 * easiest place in a payment platform to quietly break that rule.
 *
 * @param daily the trend, oldest first, with days that saw nothing OMITTED rather than zero-filled.
 *     A caller plotting a line fills its own gaps; a caller listing activity would otherwise have to
 *     filter out 364 empty rows to find the one day something happened.
 */
public record PaymentSummary(
    String currency,
    long succeededCount,
    long succeededAmountMinor,
    long failedCount,
    long failedAmountMinor,
    long refundedCount,
    long refundedAmountMinor,
    List<DailyBucket> daily
) {

    public PaymentSummary {
        daily = daily == null ? List.of() : List.copyOf(daily);
    }

    // NO DERIVED "net collected" FIELD, DELIBERATELY. It would be succeeded minus refunded, which a
    // caller can compute from the two fields above -- and over a WINDOW that subtraction can go
    // negative in a way that reads as a defect: a refund inside the window can reverse a payment
    // captured before it, so the window's refunds exceed its captures. The raw totals are always
    // non-negative and always correct; a caller that wants a net, and knows its own window
    // semantics, subtracts them. For a true balance, that is the Ledger (ADR-018), not a report.

    /** One day of the trend. Same fields as the totals, for one UTC calendar day. */
    public record DailyBucket(
        LocalDate date,
        long succeededCount,
        long succeededAmountMinor,
        long failedCount,
        long failedAmountMinor,
        long refundedCount,
        long refundedAmountMinor
    ) {
    }
}
