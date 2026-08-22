package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

/** How the application reaches {@code report_exports}. Implemented in infrastructure. */
public interface ReportExportRepository {

    ReportExport save(ReportExport export);

    /**
     * MERCHANT-SCOPED, so another tenant's export id is simply not found. The caller gets the same
     * answer as for an id that never existed, which is the rule every read in this codebase keeps:
     * an object id never authorizes access on its own.
     */
    Optional<ReportExport> findById(MerchantId merchantId, ReportExportId id);

    /** The generator's candidate list: PENDING ids, oldest first. Unlocked; {@link #claim} locks. */
    List<String> findPending(int limit);

    /**
     * Re-reads one export under a row lock, skipping it if another generator holds it.
     *
     * @return empty when the row is locked elsewhere or is no longer PENDING
     */
    Optional<ReportExport> claim(ReportExportId id);
}
