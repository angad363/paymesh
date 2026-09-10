package com.paymesh.reporting.api;

import com.paymesh.reporting.domain.ReportWindow;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Turns the {@code from} and {@code to} query parameters into a {@link ReportWindow}.
 *
 * <h2>WHY THE DEFAULT IS THIRTY DAYS RATHER THAN "EVERYTHING"</h2>
 *
 * A report with no window is an unbounded GROUP BY, and the caller who omits the parameters is
 * exactly the caller who has not thought about how much data they are asking for. Thirty days ending
 * now is the useful answer to "how am I doing", and a merchant who wants more says so.
 *
 * <p>Parsing lives at the API boundary rather than in the domain: {@link ReportWindow} is about what
 * a valid interval IS, and a malformed ISO-8601 string is a fact about HTTP.
 */
final class ReportWindows {

    static final Duration DEFAULT_SPAN = Duration.ofDays(30);

    private ReportWindows() {
    }

    /**
     * @throws IllegalArgumentException on an unparseable instant or an interval the domain refuses.
     *     The advice maps it to 400, which is right for both -- the caller wrote the window.
     */
    static ReportWindow parse(String from, String to, Clock clock) {
        Instant end = to == null ? Instant.now(clock) : instant(to, "to");
        Instant start = from == null ? end.minus(DEFAULT_SPAN) : instant(from, "from");

        return new ReportWindow(start, end);
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
