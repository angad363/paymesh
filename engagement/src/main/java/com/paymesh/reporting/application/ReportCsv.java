package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportFact;

import java.util.List;

/**
 * The one CSV this capability writes: a merchant's facts in a window.
 *
 * <h2>ONE EXPORT SHAPE, NOT ONE PER REPORT</h2>
 *
 * These rows are the data BOTH summaries are computed from, so exporting them serves either
 * question and a merchant can group them however they actually want in a spreadsheet. A
 * {@code reportType} parameter would mean a second writer producing a shape the JSON endpoints
 * already return.
 *
 * <h2>RFC 4180 QUOTING, ON EVERY FIELD, EVEN THOUGH NONE OF THEM NEEDS IT TODAY</h2>
 *
 * Every column below is an opaque id, a three-letter currency, an integer or an ISO instant, so
 * none can currently contain a comma, a quote or a newline. Relying on that is a bet that no future
 * column is free text -- and the failure mode of losing that bet is not an exception, it is a CSV
 * that parses into the wrong number of columns and a merchant reconciling against silently shifted
 * data. Quoting unconditionally costs two characters a field and removes the bet.
 */
public final class ReportCsv {

    /** The header, pinned. A merchant's importer is mapped against these names. */
    static final String HEADER =
        "occurredAt,eventType,subjectId,orderId,currency,amountMinor,recordedAt";

    private ReportCsv() {
    }

    public static String render(List<ReportFact> facts) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');

        for (ReportFact fact : facts) {
            csv.append(field(fact.occurredAt().toString())).append(',')
                .append(field(fact.eventType())).append(',')
                .append(field(fact.subjectId())).append(',')
                .append(field(fact.orderId())).append(',')
                .append(field(fact.currency())).append(',')
                .append(field(Long.toString(fact.amountMinor()))).append(',')
                .append(field(fact.recordedAt().toString()))
                .append('\n');
        }

        return csv.toString();
    }

    /**
     * @param value null becomes an EMPTY UNQUOTED field rather than {@code ""}. The distinction
     *     matters to a spreadsheet: {@code ""} is a zero-length string and a bare empty field is
     *     absent, and {@code orderId} is legitimately absent on every settlement row.
     */
    private static String field(String value) {
        if (value == null) {
            return "";
        }

        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
