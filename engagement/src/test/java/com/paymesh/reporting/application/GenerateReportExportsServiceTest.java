package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportExportStatus;
import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.domain.ReportWindow;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The generation pass, driven directly with stubs -- no context, no database, no timer.
 *
 * <p>The {@link TransactionTemplate} is a real one over a no-op transaction manager, so the
 * production code path is unchanged: the callback runs, its return value comes back. Stubbing the
 * template itself would have tested a different method.
 */
class GenerateReportExportsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final MerchantId MERCHANT = MerchantId.generate();
    private static final ReportWindow WINDOW =
        new ReportWindow(NOW.minus(Duration.ofDays(7)), NOW);

    @Test
    void rendersAPendingExportAndMarksItCompleted() {
        StubExports exports = new StubExports();
        StubFacts facts = new StubFacts(List.of(fact("payment.succeeded", 12_500)));

        ReportExport pending = exports.seed();

        var result = service(exports, facts, 100).generate();

        assertThat(result.examined()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.errored()).isZero();

        ReportExport saved = exports.saved(pending.id());

        assertThat(saved.status()).isEqualTo(ReportExportStatus.COMPLETED);
        assertThat(saved.rowCount()).isEqualTo(1);
        assertThat(saved.completedAt()).isEqualTo(NOW);
        assertThat(saved.content()).startsWith(ReportCsv.HEADER).contains("payment.succeeded");
    }

    /** An export whose window saw nothing is a header and a zero count, not a failure. */
    @Test
    void anEmptyWindowStillCompletes() {
        StubExports exports = new StubExports();
        exports.seed();

        service(exports, new StubFacts(List.of()), 100).generate();

        ReportExport saved = exports.savedOnly();

        assertThat(saved.status()).isEqualTo(ReportExportStatus.COMPLETED);
        assertThat(saved.rowCount()).isZero();
        assertThat(saved.content()).isEqualTo(ReportCsv.HEADER + "\n");
    }

    /**
     * THE ONE FAILURE THAT MUST BE TERMINAL. A window holding more rows than the cap can never be
     * satisfied, so retrying it would burn a pass every interval forever. The reason names the
     * number, so the merchant can narrow the window rather than guess.
     */
    @Test
    void failsAnExportWhoseWindowExceedsTheCap() {
        StubExports exports = new StubExports();
        exports.seed();

        StubFacts facts = new StubFacts(List.of(
            fact("payment.succeeded", 1), fact("payment.succeeded", 2), fact("payment.failed", 3)
        ));

        var result = service(exports, facts, 2).generate();

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.completed()).isZero();

        ReportExport saved = exports.savedOnly();

        assertThat(saved.status()).isEqualTo(ReportExportStatus.FAILED);
        assertThat(saved.failureReason()).contains("more than 2");
        assertThat(saved.content()).isNull();
    }

    /** The cap is inclusive: exactly maxRows is a legal export, not one row too many. */
    @Test
    void anExportOfExactlyTheCapCompletes() {
        StubExports exports = new StubExports();
        exports.seed();

        StubFacts facts =
            new StubFacts(List.of(fact("payment.succeeded", 1), fact("payment.failed", 2)));

        service(exports, facts, 2).generate();

        assertThat(exports.savedOnly().status()).isEqualTo(ReportExportStatus.COMPLETED);
        assertThat(exports.savedOnly().rowCount()).isEqualTo(2);
    }

    /**
     * ONE BAD ROW IS ONE LATE EXPORT, NOT THE PASS. A malformed id would otherwise disable the
     * generator permanently and silently -- open item 2, avoided by parsing inside the per-item try.
     */
    @Test
    void aMalformedIdCostsOneExportRatherThanTheSweep() {
        StubExports exports = new StubExports();
        exports.pending.add("not-a-report-export-id");
        ReportExport good = exports.seed();

        var result = service(exports, new StubFacts(List.of()), 100).generate();

        assertThat(result.examined()).isEqualTo(2);
        assertThat(result.errored()).isEqualTo(1);
        assertThat(result.completed()).isEqualTo(1);
        assertThat(exports.saved(good.id()).status()).isEqualTo(ReportExportStatus.COMPLETED);
    }

    /** Claimed by a concurrent generator, or no longer PENDING. SKIP LOCKED makes it a no-op. */
    @Test
    void anExportThatCannotBeClaimedIsSkippedSilently() {
        StubExports exports = new StubExports();
        exports.pending.add("rex_" + UUID.randomUUID());

        var result = service(exports, new StubFacts(List.of()), 100).generate();

        assertThat(result.examined()).isEqualTo(1);
        assertThat(result.completed()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.errored()).isZero();
        assertThat(exports.saves).isEmpty();
    }

    /** The batch size is the repository's limit, not a filter applied after fetching everything. */
    @Test
    void asksForNoMoreCandidatesThanTheBatchSize() {
        StubExports exports = new StubExports();

        new GenerateReportExportsService(
            exports, new StubFacts(List.of()), transactions(), CLOCK, 7, 100
        ).generate();

        assertThat(exports.lastLimit).isEqualTo(7);
    }

    private static GenerateReportExportsService service(
        StubExports exports, StubFacts facts, int maxRows
    ) {
        return new GenerateReportExportsService(
            exports, facts, transactions(), CLOCK, 10, maxRows
        );
    }

    /**
     * A real template over a manager that does nothing. The service's callback runs exactly as it
     * does in production; only commit and rollback are absent, and neither is asserted here.
     */
    private static TransactionTemplate transactions() {
        return new TransactionTemplate(
            new org.springframework.transaction.support.AbstractPlatformTransactionManager() {

                @Override
                protected Object doGetTransaction() {
                    return new Object();
                }

                @Override
                protected void doBegin(
                    Object transaction,
                    org.springframework.transaction.TransactionDefinition definition
                ) {
                }

                @Override
                protected void doCommit(
                    org.springframework.transaction.support.DefaultTransactionStatus status
                ) {
                }

                @Override
                protected void doRollback(
                    org.springframework.transaction.support.DefaultTransactionStatus status
                ) {
                }
            }
        );
    }

    private static ReportFact fact(String eventType, long amountMinor) {
        return new ReportFact(
            "evt_" + UUID.randomUUID(), MERCHANT, eventType, "pi_" + UUID.randomUUID(),
            null, "USD", amountMinor, NOW.minusSeconds(60), NOW.minusSeconds(59)
        );
    }

    private static final class StubExports implements ReportExportRepository {

        private final List<String> pending = new ArrayList<>();
        private final Map<String, ReportExport> claimable = new LinkedHashMap<>();
        private final Map<String, ReportExport> saves = new LinkedHashMap<>();
        private int lastLimit;

        private ReportExport seed() {
            ReportExport export = ReportExport.request(
                ReportExportId.generate(), MERCHANT, WINDOW, NOW.minusSeconds(120)
            );

            pending.add(export.id().value());
            claimable.put(export.id().value(), export);

            return export;
        }

        private ReportExport saved(ReportExportId id) {
            return saves.get(id.value());
        }

        private ReportExport savedOnly() {
            return saves.values().iterator().next();
        }

        @Override
        public ReportExport save(ReportExport export) {
            saves.put(export.id().value(), export);

            return export;
        }

        @Override
        public Optional<ReportExport> findById(MerchantId merchantId, ReportExportId id) {
            throw new UnsupportedOperationException("not part of generation");
        }

        @Override
        public List<String> findPending(int limit) {
            lastLimit = limit;

            return List.copyOf(pending);
        }

        @Override
        public Optional<ReportExport> claim(ReportExportId id) {
            return Optional.ofNullable(claimable.get(id.value()));
        }
    }

    private record StubFacts(List<ReportFact> facts) implements ReportFactRepository {

        @Override
        public boolean saveIfAbsent(ReportFact fact) {
            throw new UnsupportedOperationException("reads only");
        }

        @Override
        public List<FactTally> tallyDaily(
            MerchantId merchantId, Set<String> eventTypes, ReportWindow window
        ) {
            throw new UnsupportedOperationException("not part of generation");
        }

        @Override
        public Optional<Instant> latestRecordedAt(MerchantId merchantId) {
            throw new UnsupportedOperationException("not part of generation");
        }

        @Override
        public List<ReportFact> findInWindow(
            MerchantId merchantId, ReportWindow window, int limit
        ) {
            return facts.stream().limit(limit).toList();
        }
    }
}
