package com.paymesh.reporting.application;

import com.paymesh.reporting.application.PaymentSummary.DailyBucket;
import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two read reports, assembled from one GROUP BY each.
 *
 * <h2>NO TRANSACTION, NO CLOCK, NO WRITES</h2>
 *
 * Everything here is a read of a projection that some other pass wrote. It takes no {@link
 * java.time.Clock} deliberately: the only timestamp in the answer is {@code asOf}, and that comes
 * from the DATA rather than from this process, which is what makes it an admission of staleness
 * rather than a claim of freshness.
 *
 * <h2>The roll-up is here rather than in a second query</h2>
 *
 * Totals are the daily buckets summed, so one trip to the database serves both the trend and the
 * headline. A second aggregate query would be a second thing to keep consistent with the first, and
 * they would be read at two different instants.
 */
public final class GetReportsService {

    private final ReportFactRepository facts;

    public GetReportsService(ReportFactRepository facts) {
        this.facts = facts;
    }

    /** SDD 19.2's payment summary: totals and a daily trend, per currency. */
    public Report<PaymentSummary> paymentSummary(MerchantId merchantId, ReportWindow window) {
        List<FactTally> tallies =
            facts.tallyDaily(merchantId, ReportFact.PAYMENT_TYPES, window);

        List<PaymentSummary> summaries = byCurrency(tallies).entrySet().stream()
            .map(entry -> paymentSummary(entry.getKey(), entry.getValue()))
            .toList();

        return new Report<>(window, asOf(merchantId), summaries);
    }

    /** SDD 19.2's settlement summary: cut, paid and returned totals, per currency. */
    public Report<SettlementSummary> settlementSummary(MerchantId merchantId, ReportWindow window) {
        List<FactTally> tallies =
            facts.tallyDaily(merchantId, ReportFact.SETTLEMENT_TYPES, window);

        List<SettlementSummary> summaries = byCurrency(tallies).entrySet().stream()
            .map(entry -> settlementSummary(entry.getKey(), entry.getValue()))
            .toList();

        return new Report<>(window, asOf(merchantId), summaries);
    }

    private Instant asOf(MerchantId merchantId) {
        return facts.latestRecordedAt(merchantId).orElse(null);
    }

    /** LinkedHashMap, so the query's ORDER BY currency survives into the response. */
    private static Map<String, List<FactTally>> byCurrency(List<FactTally> tallies) {
        Map<String, List<FactTally>> byCurrency = new LinkedHashMap<>();

        for (FactTally tally : tallies) {
            byCurrency.computeIfAbsent(tally.currency(), currency -> new ArrayList<>()).add(tally);
        }

        return byCurrency;
    }

    private static PaymentSummary paymentSummary(String currency, List<FactTally> tallies) {
        Map<LocalDate, List<FactTally>> byDay = new LinkedHashMap<>();

        for (FactTally tally : tallies) {
            byDay.computeIfAbsent(tally.day(), day -> new ArrayList<>()).add(tally);
        }

        List<DailyBucket> daily = byDay.entrySet().stream()
            .map(entry -> new DailyBucket(
                entry.getKey(),
                count(entry.getValue(), "payment.succeeded"),
                amount(entry.getValue(), "payment.succeeded"),
                count(entry.getValue(), "payment.failed"),
                amount(entry.getValue(), "payment.failed"),
                count(entry.getValue(), "refund.succeeded"),
                amount(entry.getValue(), "refund.succeeded")
            ))
            .toList();

        return new PaymentSummary(
            currency,
            count(tallies, "payment.succeeded"),
            amount(tallies, "payment.succeeded"),
            count(tallies, "payment.failed"),
            amount(tallies, "payment.failed"),
            count(tallies, "refund.succeeded"),
            amount(tallies, "refund.succeeded"),
            daily
        );
    }

    private static SettlementSummary settlementSummary(String currency, List<FactTally> tallies) {
        return new SettlementSummary(
            currency,
            count(tallies, "settlement.batch_cut"),
            amount(tallies, "settlement.batch_cut"),
            count(tallies, "payout.paid"),
            amount(tallies, "payout.paid"),
            count(tallies, "payout.returned"),
            amount(tallies, "payout.returned")
        );
    }

    private static long count(List<FactTally> tallies, String eventType) {
        return sum(tallies, eventType, FactTally::factCount);
    }

    private static long amount(List<FactTally> tallies, String eventType) {
        return sum(tallies, eventType, FactTally::amountMinor);
    }

    private static long sum(
        List<FactTally> tallies,
        String eventType,
        java.util.function.ToLongFunction<FactTally> field
    ) {
        return tallies.stream()
            .filter(tally -> tally.eventType().equals(eventType))
            .mapToLong(field)
            .sum();
    }
}
