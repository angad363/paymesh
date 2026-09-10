package com.paymesh.audit.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The half-open interval an audit export is scoped by: {@code [from, to)}.
 *
 * <p>Audit's own copy of Reporting's {@code ReportWindow}, restated rather than imported so Audit
 * depends on no other capability (ADR-002, {@code ModuleBoundaryTest}). Same reasoning: two adjacent
 * windows must partition time rather than overlap it, and a bound stated once in a type all callers
 * take is what stops one of them getting it wrong.
 *
 * @param from inclusive
 * @param to exclusive
 */
public record AuditWindow(Instant from, Instant to) {

    /**
     * The largest window one export may ask for. An export over an unbounded window is an unbounded
     * TEXT column, reachable by a caller who passes {@code from=1970}. A year is generous.
     */
    public static final long MAX_DAYS = 366;

    public AuditWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("An audit window needs both a from and a to");
        }

        if (!from.isBefore(to)) {
            throw new IllegalArgumentException(
                "An audit window must end after it starts, got from=" + from + " to=" + to
            );
        }

        if (from.plus(Duration.ofDays(MAX_DAYS)).isBefore(to)) {
            throw new IllegalArgumentException(
                "An audit window cannot exceed " + MAX_DAYS + " days, got from=" + from + " to=" + to
            );
        }
    }
}
