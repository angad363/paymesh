package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportExportStatus;

/**
 * The CSV was asked for and the export has not been rendered yet.
 *
 * <p>A 409 rather than a 404: the resource exists, the representation does not yet. The merchant's
 * correct move is to re-read the JSON view and wait, and a 404 would tell them to stop asking.
 */
public final class ReportExportNotReadyException extends RuntimeException {

    public ReportExportNotReadyException(ReportExportId id, ReportExportStatus status) {
        super("Report export " + id.value() + " is " + status + " and has no file to download");
    }
}
