package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembly, driven directly with a stub repository -- no context, no database.
 *
 * <p>The roll-up from daily cells to headline totals is the part that would be silently wrong: a
 * summary that counted only the last day, or that summed across currencies, would still return
 * plausible numbers. So the fixtures below deliberately span two days AND two currencies.
 */
class GetReportsServiceTest {

    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final LocalDate DAY_ONE = LocalDate.of(2026, 8, 16);
    private static final LocalDate DAY_TWO = LocalDate.of(2026, 8, 17);
    private static final Instant RECORDED = Instant.parse("2026-08-17T12:00:00Z");

    private static final ReportWindow WINDOW = new ReportWindow(
        Instant.parse("2026-08-16T00:00:00Z"), Instant.parse("2026-08-18T00:00:00Z")
    );

    @Test
    void totalsAreTheDailyBucketsSummed() {
        StubFacts facts = new StubFacts(List.of(
            new FactTally("USD", DAY_ONE, "payment.succeeded", 2, 30_000),
            new FactTally("USD", DAY_TWO, "payment.succeeded", 1, 12_000),
            new FactTally("USD", DAY_TWO, "payment.failed", 3, 9_000),
            new FactTally("USD", DAY_TWO, "refund.succeeded", 1, 5_000)
        ));

        PaymentSummary summary =
            new GetReportsService(facts).paymentSummary(MERCHANT, WINDOW).currencies().getFirst();

        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.succeededCount()).isEqualTo(3);
        assertThat(summary.succeededAmountMinor()).isEqualTo(42_000);
        assertThat(summary.failedCount()).isEqualTo(3);
        assertThat(summary.failedAmountMinor()).isEqualTo(9_000);
        assertThat(summary.refundedCount()).isEqualTo(1);
        assertThat(summary.refundedAmountMinor()).isEqualTo(5_000);
    }

    /**
     * THE ONE THAT WOULD BE SILENTLY WRONG. Two currencies must produce two summaries, never one
     * with their amounts added -- a payment platform that sums USD and EUR reports a number that
     * means nothing and looks fine.
     */
    @Test
    void keepsCurrenciesApart() {
        StubFacts facts = new StubFacts(List.of(
            new FactTally("EUR", DAY_ONE, "payment.succeeded", 1, 10_000),
            new FactTally("USD", DAY_ONE, "payment.succeeded", 1, 20_000)
        ));

        List<PaymentSummary> summaries =
            new GetReportsService(facts).paymentSummary(MERCHANT, WINDOW).currencies();

        assertThat(summaries).hasSize(2);
        assertThat(summaries).extracting(PaymentSummary::currency)
            .containsExactlyInAnyOrder("EUR", "USD");
        assertThat(summaries).extracting(PaymentSummary::succeededAmountMinor)
            .containsExactlyInAnyOrder(10_000L, 20_000L);
    }

    /** Days with nothing are absent, so the trend is activity rather than 364 empty rows. */
    @Test
    void reportsOneBucketPerDayThatSawSomething() {
        StubFacts facts = new StubFacts(List.of(
            new FactTally("USD", DAY_ONE, "payment.succeeded", 1, 10_000),
            new FactTally("USD", DAY_TWO, "payment.failed", 2, 4_000)
        ));

        PaymentSummary summary =
            new GetReportsService(facts).paymentSummary(MERCHANT, WINDOW).currencies().getFirst();

        assertThat(summary.daily()).hasSize(2);
        assertThat(summary.daily().getFirst().date()).isEqualTo(DAY_ONE);
        assertThat(summary.daily().getFirst().succeededAmountMinor()).isEqualTo(10_000);
        assertThat(summary.daily().getFirst().failedCount()).isZero();
        assertThat(summary.daily().getLast().date()).isEqualTo(DAY_TWO);
        assertThat(summary.daily().getLast().failedCount()).isEqualTo(2);
    }

    @Test
    void settlementSummaryCountsCutPaidAndReturned() {
        StubFacts facts = new StubFacts(List.of(
            new FactTally("USD", DAY_ONE, "settlement.batch_cut", 3, 90_000),
            new FactTally("USD", DAY_TWO, "payout.paid", 2, 60_000),
            new FactTally("USD", DAY_TWO, "payout.returned", 1, 10_000)
        ));

        SettlementSummary summary = new GetReportsService(facts)
            .settlementSummary(MERCHANT, WINDOW).currencies().getFirst();

        assertThat(summary.batchesCut()).isEqualTo(3);
        assertThat(summary.cutAmountMinor()).isEqualTo(90_000);
        assertThat(summary.batchesPaid()).isEqualTo(2);
        assertThat(summary.paidAmountMinor()).isEqualTo(60_000);
        assertThat(summary.batchesReturned()).isEqualTo(1);
        assertThat(summary.returnedAmountMinor()).isEqualTo(10_000);
    }

    /** Each report asks for its own subset; neither may see the other's facts. */
    @Test
    void eachReportQueriesOnlyItsOwnEventTypes() {
        StubFacts facts = new StubFacts(List.of());
        GetReportsService reports = new GetReportsService(facts);

        reports.paymentSummary(MERCHANT, WINDOW);
        assertThat(facts.lastEventTypes).isEqualTo(ReportFact.PAYMENT_TYPES);

        reports.settlementSummary(MERCHANT, WINDOW);
        assertThat(facts.lastEventTypes).isEqualTo(ReportFact.SETTLEMENT_TYPES);
    }

    /**
     * asOf is the newest fact, not the read time. A merchant with no facts gets null rather than a
     * timestamp implying an up-to-date empty report.
     */
    @Test
    void reportsHowStaleTheProjectionIs() {
        StubFacts withFacts = new StubFacts(List.of(
            new FactTally("USD", DAY_ONE, "payment.succeeded", 1, 1)
        ));

        assertThat(new GetReportsService(withFacts).paymentSummary(MERCHANT, WINDOW).asOf())
            .isEqualTo(RECORDED);

        StubFacts empty = new StubFacts(List.of());
        empty.latest = null;

        assertThat(new GetReportsService(empty).paymentSummary(MERCHANT, WINDOW).asOf()).isNull();
    }

    /** The window travels into the response unchanged, so a client can echo what it asked for. */
    @Test
    void carriesTheRequestedWindow() {
        Report<PaymentSummary> report =
            new GetReportsService(new StubFacts(List.of())).paymentSummary(MERCHANT, WINDOW);

        assertThat(report.window()).isEqualTo(WINDOW);
    }

    private static final class StubFacts implements ReportFactRepository {

        private final List<FactTally> tallies;
        private Instant latest = RECORDED;
        private Set<String> lastEventTypes;

        private StubFacts(List<FactTally> tallies) {
            this.tallies = tallies;
        }

        @Override
        public boolean saveIfAbsent(ReportFact fact) {
            throw new UnsupportedOperationException("reads only");
        }

        @Override
        public List<FactTally> tallyDaily(
            MerchantId merchantId, Set<String> eventTypes, ReportWindow window
        ) {
            lastEventTypes = eventTypes;

            List<FactTally> matching = new ArrayList<>();

            for (FactTally tally : tallies) {
                if (eventTypes.contains(tally.eventType())) {
                    matching.add(tally);
                }
            }

            return matching;
        }

        @Override
        public Optional<Instant> latestRecordedAt(MerchantId merchantId) {
            return Optional.ofNullable(latest);
        }

        @Override
        public List<ReportFact> findInWindow(
            MerchantId merchantId, ReportWindow window, int limit
        ) {
            throw new UnsupportedOperationException("not part of a summary");
        }
    }
}
