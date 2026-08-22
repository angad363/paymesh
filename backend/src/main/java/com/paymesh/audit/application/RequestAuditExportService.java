package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;
import com.paymesh.audit.domain.AuditWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

/**
 * Records the export request and returns; the CSV is rendered by {@link GenerateAuditExportsService}
 * on its own pass. The same record-then-generate shape Reporting's export has, for the same reason:
 * the work is proportional to a window the caller chooses, so it must not hold a request thread.
 *
 * <p>Single insert, so the ambient request transaction is enough -- no {@code TransactionTemplate}.
 */
public final class RequestAuditExportService {

    private final AuditExportRepository exports;
    private final Clock clock;

    public RequestAuditExportService(AuditExportRepository exports, Clock clock) {
        this.exports = exports;
        this.clock = clock;
    }

    public AuditExport request(String requestedBy, MerchantId merchantFilter, AuditWindow window) {
        return exports.save(
            AuditExport.request(
                AuditExportId.generate(), requestedBy, merchantFilter, window, Instant.now(clock)
            )
        );
    }
}
