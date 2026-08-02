package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundCallbackOutcome;
import com.paymesh.refund.domain.RefundEvent;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundOutcome;
import com.paymesh.refund.domain.RefundStateChange;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The provider's word on a refund, recorded and applied.
 *
 * <h2>THREE GUARDS, IN THIS ORDER, AND EACH CATCHES SOMETHING THE OTHERS CANNOT</h2>
 *
 * <ol>
 *   <li><b>Deduplication</b> by {@code (provider, external_event_id)}. A WRITE, not a read: two
 *       concurrent deliveries of one callback both find nothing on a read, and only
 *       {@code pk_refund_callbacks} can decide between them. At-least-once delivery makes this the
 *       normal case rather than an exotic one.</li>
 *   <li><b>Ordering</b> against the provider's clock. A retry of an older event must not overwrite
 *       a newer decision -- ADR-012's rule, applied here for the same reason. A refund that failed
 *       and was then retried successfully must not read FAILED because the first callback arrived
 *       late.</li>
 *   <li><b>The state machine.</b> {@code Refund.succeed} and {@code fail} accept only PROCESSING,
 *       so a callback for a refund that is already terminal is recorded and not applied. This is
 *       the guard that also refuses a success for a refund PayMesh never submitted.</li>
 * </ol>
 *
 * All four outcomes are a 200. A duplicate is not the provider's error and neither is a late
 * delivery; answering 4xx would make a correct provider retry forever.
 *
 * <h2>The row lock is not optional</h2>
 *
 * {@code findForUpdate} rather than a plain read. Two callbacks for one refund -- a success and a
 * failure, or two retries -- can arrive at the same instant, and without the lock both read
 * PROCESSING, both pass the state machine, and the second overwrites the first outside any ordering
 * check. SDD 16.6's first line asks for exactly this.
 */
public final class RecordRefundCallbackService {

    private static final Logger log = LoggerFactory.getLogger(RecordRefundCallbackService.class);

    private static final int REFUND_OUTCOME_VERSION = 1;

    private final RefundRepository refunds;
    private final RefundStateHistoryRepository history;
    private final RefundCallbackRepository callbacks;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RecordRefundCallbackService(
        RefundRepository refunds,
        RefundStateHistoryRepository history,
        RefundCallbackRepository callbacks,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.refunds = refunds;
        this.history = history;
        this.callbacks = callbacks;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public RefundCallbackOutcome record(RecordRefundCallbackCommand command) {
        RefundEvent event = command.event();
        Instant now = Instant.now(clock);

        return transactions.execute(status -> {
            // The lock is taken FIRST, before the dedup insert, so that two deliveries for one
            // refund serialize here rather than racing between the dedup and the state read.
            Optional<Refund> found = refunds.findForUpdate(RefundId.from(event.refundId()));

            if (found.isEmpty()) {
                // A callback for a refund that does not exist. Not applied and NOT recorded --
                // refund_callbacks has a foreign key to refunds, so there is nowhere to put it. A
                // 404 would tell an unauthenticated-by-token caller which refund ids are real, so
                // the route answers the ordinary 200 and logs.
                log.warn(
                    "Refund callback for an unknown refund provider={} refundId={} eventId={}",
                    command.provider(), event.refundId(), event.externalEventId()
                );

                return RefundCallbackOutcome.NOT_APPLICABLE;
            }

            Refund refund = found.get();

            if (!callbacks.record(command.provider(), event, command.payloadHash(), now)) {
                log.debug(
                    "Duplicate refund callback provider={} eventId={}",
                    command.provider(), event.externalEventId()
                );

                return RefundCallbackOutcome.DUPLICATE;
            }

            Optional<Instant> latest = callbacks.latestAppliedAt(event.refundId());

            if (latest.isPresent() && event.occurredAt().isBefore(latest.get())) {
                log.info(
                    "Refusing a stale refund callback refundId={} occurredAt={} latestApplied={}",
                    event.refundId(), event.occurredAt(), latest.get()
                );

                return RefundCallbackOutcome.STALE;
            }

            if (refund.status() != RefundStatus.PROCESSING) {
                log.info(
                    "Refund callback for a refund that is already {} refundId={}",
                    refund.status(), event.refundId()
                );

                return RefundCallbackOutcome.NOT_APPLICABLE;
            }

            apply(refund, event, now);

            return RefundCallbackOutcome.APPLIED;
        });
    }

    private void apply(Refund refund, RefundEvent event, Instant now) {
        RefundStatus from = refund.status();

        Refund settled = event.outcome() == RefundOutcome.SUCCEEDED
            ? refund.succeed(event.providerReference(), now)
            : refund.fail(event.failureCode(), event.failureMessage(), now);

        Refund saved = refunds.save(settled);

        history.append(new RefundStateChange(
            saved.merchantId(), saved.refundId(), from, saved.status(),
            RefundStateChange.ActorType.PROVIDER, null,
            saved.status() == RefundStatus.FAILED ? saved.failureCode() : null,
            event.occurredAt()
        ));

        outbox.append(announcement(saved, from, event));
    }

    /**
     * {@code refund.succeeded} or {@code refund.failed}. SDD 16.5's names.
     *
     * <h2>THE EVENT IS THE ONLY WAY ANYTHING ELSE LEARNS THIS</h2>
     *
     * Two consumers wait on {@code refund.succeeded}, and neither could be a method call:
     * <ul>
     *   <li>the <b>Ledger</b> posts the reversal journal, and nothing may reach into the Ledger
     *       (ADR-018) -- every posting traces to an event;</li>
     *   <li><b>Payment</b> moves {@code refunded_amount_minor} and its own status, and Refund may
     *       not write Payment's table any more than Payment may write Order's (ADR-016).</li>
     * </ul>
     * Both consume a {@code Map} and import nothing from Refund, so the arrows stay one-way and
     * Refund remains a leaf.
     * <p>
     * One payload shape for both outcomes, which is the bug ADR-016 section 6 had to fix in
     * Payment's events after the fact: two shapes behind one version number, distinguishable only
     * by reading the type. Uniform from the start here.
     */
    private static OutboxEvent announcement(Refund refund, RefundStatus from, RefundEvent event) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("refundId", refund.refundId().value());
        payload.put("merchantId", refund.merchantId().value());
        payload.put("paymentIntentId", refund.paymentIntentId());
        payload.put("amountMinor", refund.amountMinor());
        payload.put("currency", refund.currency());
        payload.put("merchantReference", refund.merchantReference());
        payload.put("providerReference", refund.providerReference());
        payload.put("failureCode", refund.failureCode());
        payload.put("previousStatus", from.name());
        payload.put("status", refund.status().name());
        // The provider's clock, the same key and meaning Payment's events use: when the authority
        // that decided this says it happened. The envelope's own occurredAt is when PayMesh
        // recorded it, and on a late delivery those are genuinely different facts.
        payload.put("occurredAt", event.occurredAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            refund.merchantId(),
            "REFUND",
            refund.refundId().value(),
            refund.status() == RefundStatus.SUCCEEDED ? "refund.succeeded" : "refund.failed",
            REFUND_OUTCOME_VERSION,
            payload,
            event.occurredAt()
        );
    }
}
