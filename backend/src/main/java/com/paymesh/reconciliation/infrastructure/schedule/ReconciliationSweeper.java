package com.paymesh.reconciliation.infrastructure.schedule;

import com.paymesh.reconciliation.application.ProviderReportUnavailableException;
import com.paymesh.reconciliation.application.ReconcileProviderDayService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The timer, and nothing else -- the shape {@code OrderExpirySweeper} established and every
 * scheduled job here copies.
 * <p>
 * A scheduled job may carry framework annotations, and that is precisely why no logic may live in
 * one: anything inside a {@code @Scheduled} method can only be exercised by booting a context and
 * waiting for a clock to tick. Every rule reconciliation applies lives in
 * {@link ReconcileProviderDayService}, an ordinary object taking an injected {@code Clock} that a
 * plain JUnit test drives directly. If a condition or a loop ever appears in this file, it is in the
 * wrong file.
 * <p>
 * {@code fixedDelay}, not {@code fixedRate}: the next pass starts a fixed gap after the previous one
 * FINISHES, so a pass working through a large day is never re-entered while still running. Two
 * overlapping passes would still be correct -- the deterministic event id makes a repeated replay a
 * duplicate -- but correct and pointless is still pointless.
 */
public final class ReconciliationSweeper {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationSweeper.class);

    private final ReconcileProviderDayService reconcile;

    public ReconciliationSweeper(ReconcileProviderDayService reconcile) {
        this.reconcile = reconcile;
    }

    /**
     * THE ONE BRANCH THIS FILE IS ALLOWED, and it is error handling rather than logic.
     * <p>
     * A {@code @Scheduled} method that throws is logged by Spring and the job continues on its next
     * tick, so catching here changes nothing about scheduling. What it changes is the message: an
     * unreachable provider becomes one readable ERROR naming the day, instead of a stack trace from
     * the scheduler that reads like a crash. It matters because <b>this is the failure that looks
     * like success</b> -- a provider nobody can reach repairs nothing, silently, forever.
     */
    @Scheduled(
        fixedDelayString = "${paymesh.reconciliation.interval}",
        initialDelayString = "${paymesh.reconciliation.initial-delay}"
    )
    public void reconcile() {
        try {
            ReconcileProviderDayService.ReconciliationResult result = reconcile.reconcile();

            if (result.examined() > 0) {
                log.info(
                    "Reconciliation examined={} repaired={} unresolved={} errored={}",
                    result.examined(), result.repaired(), result.unresolved(), result.errored()
                );
            }
        } catch (ProviderReportUnavailableException unavailable) {
            log.error(
                "Reconciliation could not read the provider's report date={} -- NOTHING WAS "
                    + "RECONCILED on this pass. A payment the provider collected and PayMesh timed "
                    + "out stays FAILED until this succeeds.",
                unavailable.date(), unavailable
            );
        }
    }
}
