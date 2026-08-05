package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
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
 * Fails payment intents that have sat in PROCESSING longer than a provider could plausibly take
 * (ADR-015). This is the exit that state has never had.
 *
 * <h2>What it is compensating for</h2>
 *
 * PROCESSING is deliberately uncancellable: an in-flight attempt may already have succeeded at the
 * provider, so a local cancel could erase a payment that really happened (ADR-011 section 5). The
 * only way out is a callback -- and a callback may never arrive. Until now a lost callback stranded
 * the intent, and because the intent holds its order's only live slot, it stranded the order too:
 * no second intent, no route back through the API, recovery by hand. It was the largest known hole
 * in the design and it is open item 2.
 *
 * <h2>What it costs, stated plainly</h2>
 *
 * <b>Timing out to FAILED says "we believe it did not happen", and there is no evidence for that
 * belief.</b> If the attempt really did succeed at the provider, PayMesh now records a real payment
 * as failed, releases the order's slot, and the merchant may create a second intent and collect a
 * second time. That is a worse outcome than the stuck order this fixes, and the only reasons it is
 * an acceptable trade are that the age is generous enough to make the case rare, that every trace
 * needed to find it is preserved, and that the stuck order is CERTAIN while the lost payment is not.
 * <p>
 * ADR-015 argues it in full, including why a distinct non-terminal state was rejected. What follows
 * are the three things this class does to keep the bad case findable:
 * <ol>
 *   <li><b>A failure code that names the cause and does not pretend to be a decline.</b>
 *       {@code provider_no_response} is not {@code do_not_honour}: nobody may read this row as the
 *       issuer having refused, because the issuer said nothing at all.</li>
 *   <li><b>The attempt row is left completely alone.</b> Its {@code provider_reference} is what a
 *       reconciler matches against the provider's own file, and -- critically -- its
 *       {@code last_provider_event_at} is ADR-012's monotonic ordering guard. Writing a synthetic
 *       timestamp into it would make a genuine late callback judge itself STALE and vanish, which
 *       would destroy the one piece of evidence that the belief was wrong.</li>
 *   <li><b>A late callback still lands and is still recorded.</b> Arriving against a now-FAILED
 *       intent it is refused as IGNORED_TERMINAL and written to {@code provider_callbacks} anyway --
 *       a SUCCEEDED callback with that outcome IS the divergence, and it is queryable.</li>
 * </ol>
 * <b>Reconciliation does not exist</b> (SDD 21.4, 24.1). Nothing reads those traces yet. They are
 * written so that the job which will read them has something to read.
 *
 * <h2>Shape</h2>
 *
 * A plain object with no Spring annotation; {@code ProcessingTimeoutSweeper} owns the timer and does
 * nothing but call {@link #sweep()}. Tenant-agnostic and tenant-safe for the same reasons
 * {@code ExpireOrdersService} is: the merchant is read off each candidate row and scopes every write.
 * Batched, one transaction per intent, and idempotent -- the re-check under the row lock means a
 * second run finds FAILED, is not eligible, and writes nothing.
 */
public final class TimeOutProcessingPaymentsService {

    private static final Logger log = LoggerFactory.getLogger(TimeOutProcessingPaymentsService.class);

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_FAILED_VERSION = 1;

    /**
     * DELIBERATELY NOT A DECLINE CODE. A provider's own vocabulary -- {@code do_not_honour},
     * {@code insufficient_funds} -- would claim the issuer answered. It did not, and the whole point
     * of this row is that PayMesh gave up waiting. Anyone reading it, human or reconciler, must be
     * able to tell those two apart at a glance.
     */
    /**
     * Moved to {@link PaymentIntent#TIMEOUT_FAILURE_CODE} by ADR-026 and aliased here so this file
     * still reads the same. The aggregate now branches on this value -- a payment failed with it is
     * the one terminal state a late provider outcome may still revise, because it records that
     * nobody answered rather than that something happened. One definition, because two would
     * eventually disagree and the disagreement would silently disable that revision.
     */
    private static final String TIMEOUT_FAILURE_CODE = PaymentIntent.TIMEOUT_FAILURE_CODE;

    private static final String TIMEOUT_FAILURE_MESSAGE =
        "The provider did not report an outcome within the allowed time. "
            + "This attempt may still have succeeded at the provider and needs reconciliation.";

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration age;
    private final int batchSize;

    public TimeOutProcessingPaymentsService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock,
        Duration age,
        int batchSize
    ) {
        if (age == null || age.isZero() || age.isNegative()) {
            throw new IllegalArgumentException("Processing timeout age must be a positive duration");
        }

        if (batchSize < 1) {
            throw new IllegalArgumentException("Processing timeout batch size must be at least 1");
        }

        this.paymentIntents = paymentIntents;
        this.history = history;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
        this.age = age;
        this.batchSize = batchSize;
    }

    /**
     * One pass. Returns what it did, so the scheduler can log it and a test can assert it without
     * reading the database.
     */
    public SweepResult sweep() {
        Instant now = Instant.now(clock);

        // THE AGE CHECK, AND IT IS THE WHOLE SAFETY MARGIN. Everything this class does rests on the
        // cutoff being far enough in the past that a provider which was going to answer already has.
        // Widening it costs stranded orders staying stranded a little longer; narrowing it costs
        // real payments being recorded as failed. Those two are not symmetrical.
        Instant cutoff = now.minus(age);

        // IDENTIFIERS, so there is nothing left to map before the per-item boundary below.
        List<String> candidates = paymentIntents.findStrandedInProcessing(cutoff, batchSize);

        int failed = 0;
        int held = 0;
        int errored = 0;

        for (String candidate : candidates) {
            try {
                // Parsed inside the try: PaymentIntentId.from validates, and payment_intents
                // .payment_intent_id has no format CHECK. Open item 2.
                if (timeOutOne(PaymentIntentId.from(candidate), cutoff, now)) {
                    failed++;
                } else {
                    held++;
                }
            } catch (RuntimeException failure) {
                // One bad row must not stop the sweep. Candidates come back longest-stranded first,
                // so an intent that fails every time would otherwise sit at the head of every batch
                // and starve everything behind it. Nothing partial survives its rollback, so the
                // next sweep retries it safely.
                errored++;
                // The merchant is no longer to hand -- the candidate query returns ids only, so a
                // row that cannot be mapped has no merchant to name. The intent id is enough to
                // find it, and needing the id alone is exactly what makes this branch reachable
                // for a row the mapper chokes on.
                log.warn(
                    "Could not time out payment intent paymentIntentId={}",
                    candidate, failure
                );
            }
        }

        if (failed > 0) {
            // WARN, not INFO. Every row this touches is a payment whose real outcome is unknown, and
            // a platform routinely timing payments out is telling you something about its provider
            // integration. This should be noisy.
            log.warn(
                "Timed out {} payment intent(s) that never received a provider outcome "
                    + "within {}. Each may have succeeded at the provider.",
                failed, age
            );
        }

        return new SweepResult(candidates.size(), failed, held, errored);
    }

    /**
     * One intent, in its own transaction.
     *
     * @return true if it was moved to FAILED, false if it turned out not to be eligible
     */
    private boolean timeOutOne(PaymentIntentId paymentIntentId, Instant cutoff, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            // THE NO-MERCHANT LOCKING READ, and it is the right one. This caller has no tenant for
            // exactly the reason a provider callback has none -- there is no token behind it -- and
            // the merchant is derived from the row. Every write below is scoped by that derived
            // merchant.
            //
            // The lock is also what serializes this against a callback arriving at the same instant.
            // Whichever takes the row first wins; the other re-reads its committed state and finds
            // itself ineligible. Without it, a callback and a timeout could both judge the intent
            // PROCESSING and both write -- the worst possible pair of writes on this table.
            PaymentIntent intent = paymentIntents.findForProviderCallbackForUpdate(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));

            // THE IDEMPOTENCY GUARD AND THE RACE GUARD, IN ONE. A second sweep finds FAILED and
            // stops before writing anything. A callback that landed between the candidate query and
            // this lock has moved the intent out of PROCESSING, and the timeout must lose to it --
            // the provider's answer is always better evidence than the absence of one.
            //
            // THE AGE IS RE-CHECKED HERE TOO, and that is not redundant. A re-confirm after a 3DS
            // challenge puts the intent back into PROCESSING with a FRESH updatedAt; without this
            // line a candidate selected on the old timestamp would be failed on the strength of a
            // wait that has already been restarted.
            if (intent.status() != PaymentIntentStatus.PROCESSING
                || intent.updatedAt().isAfter(cutoff)) {
                return false;
            }

            PaymentIntentStatus from = intent.status();

            // The aggregate's own PROCESSING guard runs again inside fail(). Three checks of the
            // same fact is not carelessness: the query filters, this decides, and the aggregate
            // refuses -- and only the last of the three applies to every caller.
            //
            // THE ATTEMPT ROW IS NOT TOUCHED. See the class javadoc: writing a synthetic
            // last_provider_event_at would make a genuine late callback judge itself stale, which
            // would delete the evidence that this timeout was wrong. The attempt stays PROCESSING,
            // holding the provider_reference a reconciler needs, and the intent's FAILED status is
            // where the platform's belief is recorded.
            PaymentIntent saved = paymentIntents.save(
                intent.fail(TIMEOUT_FAILURE_CODE, TIMEOUT_FAILURE_MESSAGE, now)
            );

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                from,
                saved.status(),
                // SYSTEM, and actorId is null. Not PROVIDER: no provider said anything, and marking
                // this row PROVIDER would be the platform putting words in its mouth. The distinction
                // is the first thing anyone auditing a disputed payment will look for.
                PaymentStateChange.ActorType.SYSTEM,
                null,
                TIMEOUT_FAILURE_CODE,
                now
            ));

            outbox.append(paymentFailed(saved, from, now));

            return true;
        }));
    }

    /**
     * SDD 22.1 names this event, and the provider path emits the same one. A consumer cares that the
     * payment failed; {@code failureCode} is what tells it this was a timeout rather than a decline,
     * and it must not be dropped from the payload for that reason.
     */
    private static OutboxEvent paymentFailed(
        PaymentIntent intent,
        PaymentIntentStatus from,
        Instant occurredAt
    ) {
        // HashMap, not Map.of: customerId is legitimately absent on a guest checkout and Map.of
        // rejects a null value.
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", intent.paymentIntentId().value());
        payload.put("merchantId", intent.merchantId().value());
        payload.put("orderId", intent.orderId());
        payload.put("customerId", intent.customerId());
        payload.put("amountMinor", intent.amountMinor());
        payload.put("capturedAmountMinor", intent.capturedAmountMinor());
        payload.put("currency", intent.currency());
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        payload.put("failureCode", intent.failureCode());
        payload.put("failureMessage", intent.failureMessage());
        payload.put("failedAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.failed",
            PAYMENT_FAILED_VERSION,
            payload,
            occurredAt
        );
    }

    /**
     * What one sweep did.
     *
     * @param examined how many candidates the query returned
     * @param failed   how many actually moved to FAILED. <b>Every one of these is a payment whose
     *                 real outcome is unknown</b>
     * @param held     how many were skipped: a callback arrived first, or a re-confirm restarted the
     *                 wait between the query and the lock
     * @param errored  how many threw and were logged; retried on the next sweep
     */
    public record SweepResult(int examined, int failed, int held, int errored) {
    }
}
