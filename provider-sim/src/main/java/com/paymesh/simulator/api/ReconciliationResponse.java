package com.paymesh.simulator.api;

import com.paymesh.simulator.application.ReconciliationReport;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One UTC day of the provider's own truth.
 *
 * <h2>What this is, said plainly, because overclaiming it would be easy</h2>
 *
 * ADR-015 times a stranded PROCESSING payment out to FAILED with no evidence the payment failed, and
 * names a reconciliation job as the mitigation. That job did not exist because it had no input.
 * <b>This is the input. It is not the job.</b> Nothing here compares the provider's truth to
 * PayMesh's, nothing repairs a divergence, and no payment changes state because a report was read.
 * <p>
 * {@code countsByStatus} carries {@code TIMED_OUT}, which is the interesting bucket: those are the
 * payments the provider decided and never reported, so they are the rows a recovery job would start
 * from.
 * <p>
 * JSON rather than CSV, and no {@code Content-Disposition}. SDD 13.1 says "downloadable file", but
 * the consumer that does not exist yet is a job, not a spreadsheet, and the house content type is
 * JSON everywhere.
 */
public record ReconciliationResponse(
    LocalDate date,
    List<SimulatedPaymentResponse> payments,
    List<SimulatedRefundResponse> refunds,
    Map<String, Long> countsByStatus,
    long capturedTotalMinor,
    long refundedTotalMinor
) {

    public static ReconciliationResponse from(ReconciliationReport report) {
        return new ReconciliationResponse(
            report.date(),
            report.payments().stream().map(SimulatedPaymentResponse::from).toList(),
            report.refunds().stream().map(SimulatedRefundResponse::from).toList(),
            report.countsByStatus(),
            report.capturedTotalMinor(),
            report.refundedTotalMinor()
        );
    }
}
