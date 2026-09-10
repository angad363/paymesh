package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * Records the request and returns. The CSV is rendered by {@link GenerateReportExportsService} on
 * its own pass.
 *
 * <h2>WHY THIS IS NOT SYNCHRONOUS</h2>
 *
 * SDD 19.2 says asynchronously, and the reason holds: the work is proportional to a window the
 * caller chooses, so a synchronous version lets a merchant hold a request thread for as long as
 * their history is deep. Recording a row and returning a {@code rex_} makes the slow part happen
 * where a retry is free -- the same record-then-do shape Notification and Webhook use.
 *
 * <p>No transaction template: this is a single insert, so the ambient transaction of the request is
 * enough. Nothing else commits with it.
 */
public final class RequestReportExportService {

    private final ReportExportRepository exports;
    private final Clock clock;

    public RequestReportExportService(ReportExportRepository exports, Clock clock) {
        this.exports = exports;
        this.clock = clock;
    }

    public ReportExport request(MerchantId merchantId, ReportWindow window) {
        return exports.save(
            ReportExport.request(ReportExportId.generate(), merchantId, window, Instant.now(clock))
        );
    }
}
