package com.paymesh.reporting.domain;

import java.time.Instant;

/**
 * The half-open interval every report and every export is scoped by: {@code [from, to)}.
 *
 * <h2>HALF-OPEN, AND THAT IS THE WHOLE REASON THIS TYPE EXISTS</h2>
 *
 * Two adjacent windows must partition time, not overlap it. With a closed upper bound a payment at
 * exactly midnight is counted in both August and September, and the two monthly reports sum to more
 * than the year. Stating the convention once, in a type all three callers take, is what stops one
 * of them getting it right and another getting it wrong.
 *
 * @param from inclusive
 * @param to exclusive
 */
public record ReportWindow(Instant from, Instant to) {

    /**
     * The largest window a single request may ask for. Not a performance guess -- a report over an
     * unbounded window is an unbounded GROUP BY and an export over one is an unbounded TEXT column,
     * and both are reachable by a caller who passes {@code from=1970}. A year is generous for a
     * capability whose oldest possible fact is younger than the deployment.
     */
    public static final long MAX_DAYS = 366;

    public ReportWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("A report window needs both a from and a to");
        }

        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                "A report window must end after it starts, got from=" + from + " to=" + to
            );
        }

        if (from.plus(java.time.Duration.ofDays(MAX_DAYS)).isBefore(to)) {
            throw new IllegalArgumentException(
                "A report window cannot exceed " + MAX_DAYS + " days, got from=" + from + " to=" + to
            );
        }
    }
}
