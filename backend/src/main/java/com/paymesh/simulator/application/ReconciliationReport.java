package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedRefund;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One UTC day of the provider's own truth (SDD 13.1, "generate downloadable reconciliation files").
 *
 * <h2>What this is FOR, and what it does not do</h2>
 *
 * ADR-015 times a stranded PROCESSING payment out to FAILED with no evidence that the payment
 * failed, and names a reconciliation job as the mitigation -- a job that does not exist because it
 * had no input. <b>This is the input. It is not the job.</b>
 * <p>
 * The distinction matters and is easy to blur: nothing here compares the provider's truth to
 * PayMesh's, nothing repairs a divergence, and no payment changes state because a report was
 * generated. What exists now that did not before is a per-day statement of what the provider
 * believes, against which such a job can be written.
 *
 * @param countsByStatus payments per provider-side status. {@code TIMED_OUT} is the interesting
 *                       one: those are the payments the provider decided and never reported, so
 *                       they are the rows a recovery job would start from
 */
public record ReconciliationReport(
    LocalDate date,
    List<SimulatedPayment> payments,
    List<SimulatedRefund> refunds,
    Map<String, Long> countsByStatus,
    long capturedTotalMinor,
    long refundedTotalMinor
) {
}
