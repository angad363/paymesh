package com.paymesh.reporting.application;

import java.time.LocalDate;

/**
 * One cell of the GROUP BY every report is assembled from: how many facts of one type, for one
 * currency, on one day, and what they came to.
 *
 * <h2>ONE QUERY SHAPE FOR BOTH REPORTS</h2>
 *
 * The payment summary wants daily buckets AND totals; the settlement summary wants totals only.
 * Totals are the buckets summed, so there is one query and the roll-up happens in Java rather than
 * a second trip that would have to be kept consistent with the first.
 *
 * @param day the UTC calendar day of {@code occurred_at}. UTC because every timestamp in this
 *     codebase is, and because bucketing by the SERVER's zone would silently move a payment between
 *     days when the deployment moved region.
 */
public record FactTally(
    String currency,
    LocalDate day,
    String eventType,
    long factCount,
    long amountMinor
) {
}
