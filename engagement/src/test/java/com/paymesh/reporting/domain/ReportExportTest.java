package com.paymesh.reporting.domain;

import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportExportTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final ReportWindow WINDOW =
        new ReportWindow(NOW.minus(Duration.ofDays(7)), NOW);

    @Test
    void startsPendingWithNothingToDownload() {
        ReportExport export = request();

        assertThat(export.status()).isEqualTo(ReportExportStatus.PENDING);
        assertThat(export.content()).isNull();
        assertThat(export.rowCount()).isNull();
        assertThat(export.completedAt()).isNull();
        assertThat(export.requestedAt()).isEqualTo(NOW);
    }

    @Test
    void completingCarriesTheFileTheCountAndTheTime() {
        ReportExport completed = request().complete("header\n", 0, NOW.plusSeconds(30));

        assertThat(completed.status()).isEqualTo(ReportExportStatus.COMPLETED);
        assertThat(completed.content()).isEqualTo("header\n");
        assertThat(completed.rowCount()).isZero();
        assertThat(completed.completedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(completed.failureReason()).isNull();
    }

    /**
     * {@code ck_report_exports_completed} would refuse the row, and this refuses it a layer earlier
     * with a message that says what went wrong. A COMPLETED export with no file is a 200 for a
     * download that did not happen.
     */
    @Test
    void refusesToCompleteWithNoFile() {
        assertThatThrownBy(() -> request().complete(null, 3, NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must carry its CSV");
    }

    @Test
    void refusesANegativeRowCount() {
        assertThatThrownBy(() -> request().complete("header\n", -1, NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** Failing keeps the request time and takes no completion time -- nothing completed. */
    @Test
    void failingRecordsTheReasonAndNoFile() {
        ReportExport failed = request().fail("too many rows");

        assertThat(failed.status()).isEqualTo(ReportExportStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("too many rows");
        assertThat(failed.content()).isNull();
        assertThat(failed.rowCount()).isNull();
        assertThat(failed.completedAt()).isNull();
        assertThat(failed.requestedAt()).isEqualTo(NOW);
    }

    /** Intent methods return a new instance; the original is untouched. */
    @Test
    void isImmutable() {
        ReportExport pending = request();

        pending.complete("header\n", 0, NOW);

        assertThat(pending.status()).isEqualTo(ReportExportStatus.PENDING);
        assertThat(pending.content()).isNull();
    }

    private static ReportExport request() {
        return ReportExport.request(
            ReportExportId.generate(), MerchantId.generate(), WINDOW, NOW
        );
    }
}
