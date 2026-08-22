package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReportCsvTest {

    private static final Instant OCCURRED = Instant.parse("2026-08-16T10:00:00Z");
    private static final Instant RECORDED = Instant.parse("2026-08-16T10:00:05Z");
    private static final MerchantId MERCHANT = MerchantId.generate();

    @Test
    void anEmptyExportIsAHeaderAndNothingElse() {
        assertThat(ReportCsv.render(List.of())).isEqualTo(ReportCsv.HEADER + "\n");
    }

    @Test
    void writesOneQuotedRowPerFact() {
        String csv = ReportCsv.render(List.of(
            fact("payment.succeeded", "pi_1", "ord_1", 12_500)
        ));

        assertThat(csv.lines()).hasSize(2);
        assertThat(csv.lines().skip(1).findFirst()).hasValue(
            "\"2026-08-16T10:00:00Z\",\"payment.succeeded\",\"pi_1\",\"ord_1\",\"USD\",\"12500\","
                + "\"2026-08-16T10:00:05Z\""
        );
    }

    /**
     * An absent order is an EMPTY field, not {@code ""}. A spreadsheet reads the first as blank and
     * the second as a zero-length string, and every settlement row has no order.
     */
    @Test
    void anAbsentOrderIsAnEmptyFieldRatherThanAnEmptyString() {
        String csv = ReportCsv.render(List.of(
            fact("settlement.batch_cut", "stl_1", null, 90_000)
        ));

        assertThat(csv).contains("\"stl_1\",,\"USD\"");
    }

    /**
     * THE ASSERTION THAT PROTECTS A FUTURE COLUMN. No field can contain a quote today -- every one
     * is an id, a currency, a number or an instant. Doubling is asserted anyway, because the day a
     * free-text column arrives the failure would not be an exception, it would be a CSV that parses
     * into the wrong number of columns.
     */
    @Test
    void doublesAnEmbeddedQuote() {
        ReportFact awkward = new ReportFact(
            "evt_" + UUID.randomUUID(), MERCHANT, "payment.failed",
            "pi_\"quoted\"", null, "USD", 1, OCCURRED, RECORDED
        );

        assertThat(ReportCsv.render(List.of(awkward)))
            .contains("\"pi_\"\"quoted\"\"\"");
    }

    @Test
    void keepsTheHeaderStable() {
        assertThat(ReportCsv.HEADER)
            .as("a merchant's importer is mapped against these names")
            .isEqualTo("occurredAt,eventType,subjectId,orderId,currency,amountMinor,recordedAt");
    }

    private static ReportFact fact(
        String eventType, String subjectId, String orderId, long amountMinor
    ) {
        return new ReportFact(
            "evt_" + UUID.randomUUID(), MERCHANT, eventType, subjectId, orderId,
            "USD", amountMinor, OCCURRED, RECORDED
        );
    }
}
