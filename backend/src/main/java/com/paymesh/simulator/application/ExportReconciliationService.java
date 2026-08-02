package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.RefundStatus;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedRefund;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds the provider's truth file for one day.
 * <p>
 * <b>The day is UTC, and saying so is not pedantry.</b> Every timestamp in this platform is a UTC
 * {@code Instant}; a report cut on a local calendar day would double-count or drop the payments in
 * the offset, and reconciliation is exactly the job where an off-by-one-day boundary is invisible
 * until the totals disagree by one transaction.
 */
public final class ExportReconciliationService {

    private final SimulatedPaymentRepository payments;
    private final SimulatedRefundRepository refunds;

    public ExportReconciliationService(
        SimulatedPaymentRepository payments,
        SimulatedRefundRepository refunds
    ) {
        this.payments = payments;
        this.refunds = refunds;
    }

    public ReconciliationReport export(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("A reconciliation date is required");
        }

        // Half-open [start, end): the alternative, an inclusive end at 23:59:59.999999999, drops
        // anything stamped in the nanoseconds after it. Postgres stores microseconds and Instant
        // holds nanoseconds, so that gap is real rather than theoretical.
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        List<SimulatedPayment> paymentsOfTheDay = payments.findCreatedBetween(from, to);
        List<SimulatedRefund> refundsOfTheDay = refunds.findCreatedBetween(from, to);

        return new ReconciliationReport(
            date,
            paymentsOfTheDay,
            refundsOfTheDay,
            countByStatus(paymentsOfTheDay),
            paymentsOfTheDay.stream().mapToLong(SimulatedPayment::capturedAmountMinor).sum(),
            refundsOfTheDay.stream()
                // Only the refunds that actually moved money. A FAILED refund in the totals would
                // make the file disagree with the payment rows it is meant to be reconciled against.
                .filter(refund -> refund.status() == RefundStatus.SUCCEEDED)
                .mapToLong(SimulatedRefund::amountMinor)
                .sum()
        );
    }

    /** TreeMap so the export's key order is stable, which a file meant to be diffed needs. */
    private static Map<String, Long> countByStatus(List<SimulatedPayment> payments) {
        return payments.stream().collect(Collectors.groupingBy(
            payment -> payment.status().name(), TreeMap::new, Collectors.counting()
        ));
    }
}
