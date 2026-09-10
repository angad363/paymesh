package com.paymesh.reporting.application;

import com.paymesh.reporting.domain.ReportExport;
import com.paymesh.reporting.domain.ReportExportId;
import com.paymesh.reporting.domain.ReportFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * One pass over the PENDING exports, rendering each. The same claim-then-work shape
 * {@code SendPendingNotificationsService} and {@code DeliverWebhooksService} use.
 *
 * <h2>One transaction per export, claimed with SKIP LOCKED</h2>
 *
 * Candidates are read unlocked; each id is then re-read under its own lock. One large export cannot
 * roll back the ones beside it, and a second generator takes different rows rather than queueing
 * behind this one.
 *
 * <h2>One bad row must not disable the pass</h2>
 *
 * {@link ReportExportRepository#findPending} returns raw strings and {@code ReportExportId.from} --
 * which validates and throws -- is called INSIDE the per-item try, so a malformed id costs one
 * export rather than the sweep. This is open item 2 in docs/project-status.md, avoided by
 * construction.
 *
 * <h2>Why there is no attempt budget</h2>
 *
 * Rendering rows this process can already read is deterministic: it does not fail the way an HTTP
 * delivery to someone else's server does. So a throw leaves the export PENDING and the next pass
 * tries again forever, which is correct for work that will either always succeed or always fail for
 * a reason someone must fix. The one failure that is NOT transient -- a window holding more rows
 * than a single response can carry -- is detected and made terminal here, because retrying it
 * would burn a pass every minute for a request that can never be satisfied.
 */
public final class GenerateReportExportsService {

    private static final Logger log =
        LoggerFactory.getLogger(GenerateReportExportsService.class);

    private final ReportExportRepository exports;
    private final ReportFactRepository facts;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxRows;

    public GenerateReportExportsService(
        ReportExportRepository exports,
        ReportFactRepository facts,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize,
        int maxRows
    ) {
        this.exports = exports;
        this.facts = facts;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxRows = maxRows;
    }

    public GenerationResult generate() {
        List<String> pending = exports.findPending(batchSize);

        int completed = 0;
        int failed = 0;
        int errored = 0;

        for (String candidate : pending) {
            Outcome outcome;

            try {
                outcome = generateOne(ReportExportId.from(candidate));
            } catch (RuntimeException failure) {
                // ONE BAD ROW IS ONE LATE EXPORT, NOT THE PASS. Still PENDING, picked up next time.
                log.error("Report export {} threw and will be retried", candidate, failure);

                errored++;

                continue;
            }

            switch (outcome) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case GONE -> {
                    // Claimed by a concurrent generator, or no longer PENDING, between the candidate
                    // read and the lock. SKIP LOCKED makes that a no-op.
                }
            }
        }

        return new GenerationResult(pending.size(), completed, failed, errored);
    }

    private Outcome generateOne(ReportExportId id) {
        return transactions.execute(status -> {
            ReportExport export = exports.claim(id).orElse(null);

            if (export == null) {
                return Outcome.GONE;
            }

            // maxRows + 1, so "there is more than the cap" is answered by the same query that
            // fetches the rows rather than by a second COUNT that could disagree with it.
            List<ReportFact> rows =
                facts.findInWindow(export.merchantId(), export.window(), maxRows + 1);

            if (rows.size() > maxRows) {
                String reason = "The window holds more than " + maxRows
                    + " facts; request a narrower one";

                log.warn("Report export {} refused: {}", id.value(), reason);

                exports.save(export.fail(reason));

                return Outcome.FAILED;
            }

            exports.save(export.complete(ReportCsv.render(rows), rows.size(), Instant.now(clock)));

            return Outcome.COMPLETED;
        });
    }

    private enum Outcome {
        COMPLETED,
        FAILED,
        GONE
    }

    /**
     * What one pass did, counted so the scheduled bean can log something worth reading.
     *
     * @param errored threw and was logged; the row is still PENDING and the next pass retries it
     */
    public record GenerationResult(int examined, int completed, int failed, int errored) {
    }
}
