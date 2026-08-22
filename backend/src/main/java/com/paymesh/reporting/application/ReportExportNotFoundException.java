package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExportId;

/**
 * No export with that id belongs to this merchant.
 *
 * <p>ONE EXCEPTION FOR "NEVER EXISTED" AND "SOMEONE ELSE'S", deliberately. The lookup is
 * merchant-scoped, so a cross-tenant id and a fictional id are indistinguishable to the caller --
 * saying "forbidden" for the first would confirm the id exists.
 */
public final class ReportExportNotFoundException extends RuntimeException {

    public ReportExportNotFoundException(ReportExportId id) {
        super("No report export " + id.value());
    }
}
