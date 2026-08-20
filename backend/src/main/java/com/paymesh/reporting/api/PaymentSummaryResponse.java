package com.paymesh.reporting.api;

import com.paymesh.reporting.application.PaymentSummary;
import com.paymesh.reporting.application.Report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/v1/reports/payment-summary}.
 *
 * <h2>asOf IS PART OF THE CONTRACT, NOT A DEBUG FIELD</h2>
 *
 * It is the newest fact the projection holds for this merchant, and it is null when the projection
 * holds none. A client rendering a dashboard is expected to show it -- SDD 19.2 requires the UI to
 * display an as-of timestamp or a delayed-data signal, and this is the value it displays.
 *
 * @param currencies one entry per currency; never summed across, because that would add USD to EUR
 */
record PaymentSummaryResponse(
    Instant from,
    Instant to,
    Instant asOf,
    List<CurrencySummary> currencies
) {

    static PaymentSummaryResponse from(Report<PaymentSummary> report) {
        return new PaymentSummaryResponse(
            report.window().from(),
            report.window().to(),
            report.asOf(),
            report.currencies().stream().map(CurrencySummary::from).toList()
        );
    }

    record CurrencySummary(
        String currency,
        long succeededCount,
        long succeededAmountMinor,
        long failedCount,
        long failedAmountMinor,
        long refundedCount,
        long refundedAmountMinor,
        List<DailyBucketResponse> daily
    ) {

        static CurrencySummary from(PaymentSummary summary) {
            return new CurrencySummary(
                summary.currency(),
                summary.succeededCount(),
                summary.succeededAmountMinor(),
                summary.failedCount(),
                summary.failedAmountMinor(),
                summary.refundedCount(),
                summary.refundedAmountMinor(),
                summary.daily().stream().map(DailyBucketResponse::from).toList()
            );
        }
    }

    /** One UTC day of the trend. Days with no activity are absent rather than zero-filled. */
    record DailyBucketResponse(
        LocalDate date,
        long succeededCount,
        long succeededAmountMinor,
        long failedCount,
        long failedAmountMinor,
        long refundedCount,
        long refundedAmountMinor
    ) {

        static DailyBucketResponse from(PaymentSummary.DailyBucket bucket) {
            return new DailyBucketResponse(
                bucket.date(),
                bucket.succeededCount(),
                bucket.succeededAmountMinor(),
                bucket.failedCount(),
                bucket.failedAmountMinor(),
                bucket.refundedCount(),
                bucket.refundedAmountMinor()
            );
        }
    }
}
