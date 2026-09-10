package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportWindow;

import java.time.Instant;
import java.util.List;

/**
 * A report, and the admission that it may be stale.
 *
 * <h2>asOf IS THE POINT OF THIS WRAPPER</h2>
 *
 * Delivery is asynchronous (ADR-016): an event committed a moment ago may still be sitting in
 * {@code outbox_events} unpublished, so a projection is eventually consistent by construction. The
 * phase-2 plan states the rule this type exists to keep -- "a report that reads a second stale
 * without admitting it is worse than one that admits it".
 *
 * <p>It is the newest {@code recorded_at} in the merchant's own facts, NOT the read time. "Now"
 * would claim a currency the projection does not have. A relay that has stopped shows up as an
 * {@code asOf} that stops advancing, which is exactly the signal a merchant needs and exactly what
 * a "now" would hide.
 *
 * @param asOf null when the merchant has no facts at all -- an honest "nothing projected yet"
 *     rather than a timestamp implying an up-to-date empty report
 * @param currencies one entry per currency in the window; never summed across
 */
public record Report<T>(ReportWindow window, Instant asOf, List<T> currencies) {

    public Report {
        currencies = currencies == null ? List.of() : List.copyOf(currencies);
    }
}
