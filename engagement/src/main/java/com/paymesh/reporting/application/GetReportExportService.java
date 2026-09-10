package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportExportStatus;
import com.paymesh.shared.tenant.MerchantId;

/**
 * Reads one export back: its status, or its file.
 *
 * <p>Merchant-scoped, so another tenant's id answers 404 exactly as an id that never existed does.
 */
public final class GetReportExportService {

    private final ReportExportRepository exports;

    public GetReportExportService(ReportExportRepository exports) {
        this.exports = exports;
    }

    public ReportExport get(MerchantId merchantId, ReportExportId id) {
        return exports.findById(merchantId, id)
            .orElseThrow(() -> new ReportExportNotFoundException(id));
    }

    /**
     * The CSV itself.
     *
     * @throws ReportExportNotReadyException when the export has not been rendered. Checked HERE
     *     rather than in the controller so the rule cannot be forgotten by a second caller, and
     *     because "is there a file" is a question about the export rather than about HTTP.
     */
    public String download(MerchantId merchantId, ReportExportId id) {
        ReportExport export = get(merchantId, id);

        if (export.status() != ReportExportStatus.COMPLETED) {
            throw new ReportExportNotReadyException(id, export.status());
        }

        return export.content();
    }
}
