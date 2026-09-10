package com.paymesh.audit.api;

import com.paymesh.audit.domain.AuditWindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Turns {@code from} and {@code to} parameters into an {@link AuditWindow}. Audit's copy of
 * Reporting's {@code ReportWindows}, with one extra: the list endpoint's window is optional (a
 * support engineer scanning recent events wants no bound), while the export endpoint defaults to
 * thirty days the same way Reporting does.
 *
 * <p>Parsing lives at the API boundary: {@link AuditWindow} is about what a valid interval IS, and a
 * malformed ISO-8601 string is a fact about HTTP.
 */
final class AuditWindows {

    static final Duration DEFAULT_SPAN = Duration.ofDays(30);

    private AuditWindows() {
    }

    /**
     * For the LIST filter: null when neither bound is given (scan the most recent), otherwise a
     * window. A one-sided bound is completed from the other so the domain's ordering rule still
     * holds -- an open-ended {@code from} runs to now, an open-ended {@code to} back thirty days.
     */
    static AuditWindow parseOrNull(String from, String to, Clock clock) {
        if (from == null && to == null) {
            return null;
        }

        return parse(from, to, clock);
    }

    /**
     * For the EXPORT: always a window, defaulting to the last thirty days.
     *
     * @throws IllegalArgumentException on an unparseable instant or an interval the domain refuses;
     *     the advice maps it to 400, right for both -- the caller wrote the window
     */
    static AuditWindow parse(String from, String to, Clock clock) {
        Instant end = to == null ? Instant.now(clock) : instant(to, "to");
        Instant start = from == null ? end.minus(DEFAULT_SPAN) : instant(from, "from");

        return new AuditWindow(start, end);
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                "The " + field + " parameter must be an ISO-8601 instant such as"
                    + " 2026-08-01T00:00:00Z, got: " + value,
                exception
            );
        }
    }
}
