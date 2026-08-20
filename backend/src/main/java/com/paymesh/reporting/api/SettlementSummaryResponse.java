package com.paymesh.reporting.api;

import com.paymesh.reporting.application.Report;
import com.paymesh.reporting.application.SettlementSummary;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/v1/reports/settlements}.
 *
 * <p>An AGGREGATE, which is what makes it different from {@code GET /api/v1/settlements}: that lists
 * batches with their current statuses from Settlement's own tables, this answers how much was cut,
 * landed and came back over a window. Duplicating the list here would be a second read of the same
 * rows with a weaker guarantee.
 */
record SettlementSummaryResponse(
    Instant from,
    Instant to,
    Instant asOf,
    List<CurrencySummary> currencies
) {

    static SettlementSummaryResponse from(Report<SettlementSummary> report) {
        return new SettlementSummaryResponse(
            report.window().from(),
            report.window().to(),
            report.asOf(),
            report.currencies().stream().map(CurrencySummary::from).toList()
        );
    }

    record CurrencySummary(
        String currency,
        long batchesCut,
        long cutAmountMinor,
        long batchesPaid,
        long paidAmountMinor,
        long batchesReturned,
        long returnedAmountMinor
    ) {

        static CurrencySummary from(SettlementSummary summary) {
            return new CurrencySummary(
                summary.currency(),
                summary.batchesCut(),
                summary.cutAmountMinor(),
                summary.batchesPaid(),
                summary.paidAmountMinor(),
                summary.batchesReturned(),
                summary.returnedAmountMinor()
            );
        }
    }
}
