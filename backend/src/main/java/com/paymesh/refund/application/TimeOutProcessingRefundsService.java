package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundStateChange;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fails refunds the provider never answered for.
 *
 * <h2>THE DEFECT THIS FIXES IS NOT "AN UNTIDY ROW"</h2>
 *
 * A refund whose callback never arrives stays PROCESSING forever, and a PROCESSING refund COUNTS
 * AGAINST THE CAPTURED AMOUNT (ADR-019). So a single lost callback permanently reduces what the
 * merchant can refund on that payment -- a 4000 refund that vanished blocks 4000 of head-room for
 * good, and nothing anywhere reports it. The merchant discovers it when a legitimate refund is
 * rejected as an over-refund.
 * <p>
 * Payment has had a sweeper for exactly this shape since ADR-015. Refund shipped without one, and
 * ADR-019 recorded it as the known gap. ADR-023 closes it.
 *
 * <h2>FAILING IS THE SAFE DIRECTION, AND IT IS STILL A GUESS</h2>
 *
 * The provider may have moved the money and lost the callback. Timing the refund out to FAILED then
 * means PayMesh believes no refund happened when one did -- which shows up as a refundable balance
 * that is too high, and a second refund attempt that double-refunds.
 * <p>
 * The alternative is worse in the ordinary case: leaving it PROCESSING forever holds head-room
 * hostage on EVERY lost callback, including the overwhelming majority where the provider genuinely
 * never acted. So this fails them, on a deliberately long timer, and the real answer is
 * reconciliation against the provider's own record -- which does not exist yet and is named in
 * ADR-023 as the thing this makes tolerable rather than solves.
 *
 * <h2>Ordering against a late callback</h2>
 *
 * A callback arriving after the timeout finds the refund FAILED rather than PROCESSING, so
 * {@code RecordRefundCallbackService} answers NOT_APPLICABLE and records it without applying it.
 * The row is on the record for whoever reconciles later, which is exactly what should happen.
 */
public final class TimeOutProcessingRefundsService {

    private static final Logger log = LoggerFactory.getLogger(TimeOutProcessingRefundsService.class);

    private static final int REFUND_OUTCOME_VERSION = 1;

    private static final String FAILURE_CODE = "provider_timeout";
    private static final String FAILURE_MESSAGE =
        "The provider did not report an outcome within the timeout.";

    private final RefundRepository refunds;
    private final RefundStateHistoryRepository history;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Duration age;
    private final int batchSize;
    private final Clock clock;

    public TimeOutProcessingRefundsService(
        RefundRepository refunds,
        RefundStateHistoryRepository history,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Duration age,
        int batchSize,
        Clock clock
    ) {
        this.refunds = refunds;
        this.history = history;
        this.outbox = outbox;
        this.transactions = transactions;
        this.age = age;
        this.batchSize = batchSize;
        this.clock = clock;
    }

    /**
     * @return what the pass did. {@code errored} is counted rather than thrown, so ONE BAD ROW
     *     CANNOT DISABLE THE SWEEP -- open item 2 records that exact failure in the other two
     *     sweepers, and repeating it here would be repeating a known bug on purpose.
     */
    public SweepResult sweep() {
        Instant now = Instant.now(clock);
        // IDENTIFIERS, so there is nothing left to map before the per-item boundary below.
        List<String> stale = refunds.findProcessingOlderThan(now.minus(age), batchSize);

        int failed = 0;
        int errored = 0;

        for (String candidate : stale) {
            try {
                // Parsed inside the try: RefundId.from validates, and refunds.refund_id has no
                // format CHECK. Open item 2.
                RefundId refundId = RefundId.from(candidate);

                // Each in its own transaction, so one failure does not roll back the ones before it.
                transactions.execute(status -> {
                    failOne(refundId, now);
                    return null;
                });

                failed++;
            } catch (RuntimeException exception) {
                errored++;

                log.warn(
                    "Could not time out refund refundId={}: {}",
                    candidate, exception.getMessage()
                );
            }
        }

        return new SweepResult(stale.size(), failed, errored);
    }

    private void failOne(RefundId refundId, Instant now) {
        // Re-read under a lock: a callback may have settled it between the query and here, and
        // failing a refund the provider just succeeded would be the worst outcome this class has.
        // The candidate query returns only this id, so this read is also the FIRST time the row is
        // mapped -- and it happens inside the caller's try, which is the point of open item 2.
        Refund locked = refunds.findForUpdate(refundId).orElse(null);

        if (locked == null || locked.status() != RefundStatus.PROCESSING) {
            return;
        }

        Refund timedOut = refunds.save(locked.fail(FAILURE_CODE, FAILURE_MESSAGE, now));

        history.append(new RefundStateChange(
            timedOut.merchantId(), timedOut.refundId(),
            RefundStatus.PROCESSING, RefundStatus.FAILED,
            RefundStateChange.ActorType.SYSTEM, null, FAILURE_CODE, now
        ));

        outbox.append(refundFailed(timedOut, now));

        log.warn(
            "Timed out a refund the provider never answered refundId={} merchantId={} amountMinor={}",
            timedOut.refundId().value(), timedOut.merchantId().value(), timedOut.amountMinor()
        );
    }

    /**
     * The same {@code refund.failed} shape the callback path emits, so a consumer cannot tell a
     * timeout from a decline by the envelope -- only by {@code failureCode}. One shape per event
     * name is the rule ADR-016 section 6 had to fix in Payment's events after the fact.
     */
    private static OutboxEvent refundFailed(Refund refund, Instant now) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("refundId", refund.refundId().value());
        payload.put("merchantId", refund.merchantId().value());
        payload.put("paymentIntentId", refund.paymentIntentId());
        payload.put("amountMinor", refund.amountMinor());
        payload.put("currency", refund.currency());
        payload.put("merchantReference", refund.merchantReference());
        payload.put("providerReference", refund.providerReference());
        payload.put("failureCode", refund.failureCode());
        payload.put("previousStatus", RefundStatus.PROCESSING.name());
        payload.put("status", refund.status().name());
        payload.put("occurredAt", now.toString());

        return new OutboxEvent(
            EventId.generate(),
            refund.merchantId(),
            "REFUND",
            refund.refundId().value(),
            "refund.failed",
            REFUND_OUTCOME_VERSION,
            payload,
            now
        );
    }

    /** @param errored rows that threw. Counted, never rethrown -- see {@link #sweep()}. */
    public record SweepResult(int examined, int failed, int errored) {
    }
}
