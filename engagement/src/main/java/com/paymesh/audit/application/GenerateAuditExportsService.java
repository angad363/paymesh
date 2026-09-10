package com.paymesh.audit.application;

import com.paymesh.audit.domain.AuditEvent;
import com.paymesh.audit.domain.AuditExport;
import com.paymesh.audit.domain.AuditExportId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * One pass over the PENDING audit exports, rendering each. Copied from
 * {@code GenerateReportExportsService} deliberately: same claim-then-work shape, same one-bad-row
 * discipline, same no-attempt-budget reasoning.
 *
 * <h2>One transaction per export, claimed with SKIP LOCKED</h2>
 *
 * Candidates are read unlocked; each id is re-read under its own lock. One large export cannot roll
 * back the ones beside it, and a second generator takes different rows.
 *
 * <h2>One bad row must not disable the pass</h2>
 *
 * {@link AuditExportRepository#findPending} returns raw strings and {@code AuditExportId.from} --
 * which validates and throws -- is called INSIDE the per-item try, so a malformed id costs one
 * export rather than the sweep. Open item 2 in docs/project-status.md, avoided by construction.
 */
public final class GenerateAuditExportsService {

    private static final Logger log = LoggerFactory.getLogger(GenerateAuditExportsService.class);

    private final AuditExportRepository exports;
    private final AuditEventRepository events;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxRows;

    public GenerateAuditExportsService(
        AuditExportRepository exports,
        AuditEventRepository events,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize,
        int maxRows
    ) {
        this.exports = exports;
        this.events = events;
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
                outcome = generateOne(AuditExportId.from(candidate));
            } catch (RuntimeException failure) {
                // ONE BAD ROW IS ONE LATE EXPORT, NOT THE PASS. Still PENDING, picked up next time.
                log.error("Audit export {} threw and will be retried", candidate, failure);

                errored++;

                continue;
            }

            switch (outcome) {
                case COMPLETED -> completed++;
                case FAILED -> failed++;
                case GONE -> {
                    // Claimed by a concurrent generator, or no longer PENDING. SKIP LOCKED -> no-op.
                }
            }
        }

        return new GenerationResult(pending.size(), completed, failed, errored);
    }

    private Outcome generateOne(AuditExportId id) {
        return transactions.execute(status -> {
            AuditExport export = exports.claim(id).orElse(null);

            if (export == null) {
                return Outcome.GONE;
            }

            // maxRows + 1, so "there is more than the cap" is answered by the same query that
            // fetches the rows rather than by a second COUNT that could disagree with it.
            List<AuditEvent> rows =
                events.findInWindow(export.window(), export.merchantFilter(), maxRows + 1);

            if (rows.size() > maxRows) {
                String reason = "The window holds more than " + maxRows
                    + " audit events; request a narrower one";

                log.warn("Audit export {} refused: {}", id.value(), reason);

                exports.save(export.fail(reason));

                return Outcome.FAILED;
            }

            exports.save(export.complete(AuditCsv.render(rows), rows.size(), Instant.now(clock)));

            return Outcome.COMPLETED;
        });
    }

    private enum Outcome {
        COMPLETED,
        FAILED,
        GONE
    }

    /**
     * @param errored threw and was logged; the row is still PENDING and the next pass retries it
     */
    public record GenerationResult(int examined, int completed, int failed, int errored) {
    }
}
