package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.payment.domain.ProviderCallback;
import com.paymesh.payment.domain.ProviderCallbackOutcome;
import com.paymesh.payment.domain.ProviderEvent;
import com.paymesh.payment.domain.ProviderOutcome;
import com.paymesh.payment.domain.ProviderOutcomeNotApplicableException;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The only thing that can move a payment past PROCESSING.
 * <p>
 * <b>The transition logic is here rather than in the controller on purpose</b> (design section 0.4,
 * "late"). A provider that succeeds but whose callback never arrives is out of scope, and the
 * recovery for it is a reconciliation job reading the provider's own file. That job will call this
 * service with the same command and get the same idempotent effect. A controller holding the logic
 * would have forced the job to reimplement it, and two implementations of "what does this event mean"
 * is how a payment gets applied twice.
 * <p>
 * THREE INDEPENDENT MECHANISMS ANSWER THE THREE WAYS A CALLBACK MISBEHAVES (ADR-012). None of them
 * subsumes another, and removing any one of them is not caught by the others:
 * <ol>
 *   <li><b>Duplicated</b> -- {@code pk_provider_callbacks}, with the insert inside this transaction.
 *       The duplicate loses, the transaction rolls back, nothing happened. A concurrent duplicate
 *       BLOCKS on the index entry until the first transaction resolves; if the first rolls back the
 *       second's insert succeeds and it correctly applies the event.</li>
 *   <li><b>Out of order</b> -- the aggregate's state machine (terminal states absorb) AND the
 *       monotonic {@code last_provider_event_at}. The machine alone is not enough, because it
 *       contains a cycle in which a stale event is a legal transition.</li>
 *   <li><b>Concurrent and different</b> -- a pessimistic lock on the intent row, taken before the
 *       insert. The lock orders two different callbacks; the primary key orders two identical ones.</li>
 * </ol>
 */
public final class RecordProviderCallbackService {

    /** SDD 22.1. Bump only when a payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_OUTCOME_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentAttemptRepository attempts;
    private final PaymentStateHistoryRepository history;
    private final ProviderCallbackRepository callbacks;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RecordProviderCallbackService(
        PaymentIntentRepository paymentIntents,
        PaymentAttemptRepository attempts,
        PaymentStateHistoryRepository history,
        ProviderCallbackRepository callbacks,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.attempts = attempts;
        this.history = history;
        this.callbacks = callbacks;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Judges one delivery and, if it is the real thing, applies it.
     *
     * @throws PaymentIntentNotFoundException when the callback names an intent this platform does
     *                                        not know. The API answers 404 and stores nothing --
     *                                        deliberately the one non-2xx, because the likeliest
     *                                        cause is a callback overtaking the transaction that
     *                                        created the intent and a retry is what should happen.
     */
    public ProviderCallbackOutcome record(RecordProviderCallbackCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Record Provider Callback Command cannot be null");
        }

        // ONE READING OF THE CLOCK FOR THE WHOLE DELIVERY. received_at, processed_at, the aggregate
        // timestamps and the outbox envelope all describe the same moment, and three separate reads
        // would put microsecond gaps between facts that are simultaneous by definition.
        //
        // It is deliberately NOT the provider's occurredAt. That is the provider's clock, it is what
        // the ordering guard compares, and it is stored on the callback row and in the event payload
        // -- but a transition is stamped with when THIS platform made it, or a late delivery would
        // insert a state-history row that sorts before the confirm that preceded it.
        Instant now = Instant.now(clock);

        try {
            return transactions.execute(status -> judgeAndApply(command, now));
        } catch (DuplicateProviderCallbackException duplicate) {
            // THE ROLLBACK HAS ALREADY HAPPENED, and it took everything with it: no transition, no
            // state-history row, no outbox event. The row that already exists is the record, and it
            // carries whatever outcome the FIRST delivery earned. 200, never 409 -- a provider
            // retries on non-2xx, and a conflict here is an infinite retry loop.
            return ProviderCallbackOutcome.DUPLICATE;
        }
    }

    private ProviderCallbackOutcome judgeAndApply(
        RecordProviderCallbackCommand command,
        Instant now
    ) {
        ProviderEvent event = command.event();

        // STEPS 1 AND 2, IN ONE. Resolving and locking cannot be separated: a plain read followed by
        // a lock would be two reads of a row that may have moved in between.
        PaymentIntent intent = lockIntentNamedBy(command);

        // STEPS 4 AND 5, BEFORE THE INSERT RATHER THAN AFTER IT, AND THAT IS SAFE. Both are pure
        // reads plus a pure function on the aggregate; nothing is written until the insert below. The
        // design's ordering exists so that a duplicate finds NOTHING done, and that still holds --
        // what a duplicate must not find is a WRITE, and there is none before the insert. Computing
        // first means the row is inserted once carrying its final outcome, instead of being inserted
        // with a placeholder and updated a statement later.
        Judgement judgement = judge(intent, event, now);

        // STEP 3. The transaction dies here on a duplicate, by design.
        callbacks.insert(new ProviderCallback(
            command.provider(),
            event.eventId(),
            intent.merchantId(),
            intent.paymentIntentId(),
            command.payloadHash(),
            event.redactedPayload(),
            judgement.outcome(),
            event.occurredAt(),
            now,
            now
        ));

        // STEP 6. Everything or nothing, in this transaction: the moved attempt, the moved intent,
        // exactly one timeline row and exactly one event. The outbox append sharing this commit IS
        // SDD 12.6's invariant.
        if (judgement.outcome() == ProviderCallbackOutcome.APPLIED) {
            apply(intent, judgement.moved(), command, event, now);
        }

        return judgement.outcome();
    }

    /**
     * The two refusals and the one acceptance, decided without writing anything.
     * <p>
     * Both refusals are recorded and both answer 200. A refused event that left no trace would be
     * re-judged from scratch on every re-delivery, and the IGNORED_TERMINAL row is the only record of
     * a genuine PayMesh/provider divergence.
     */
    private Judgement judge(PaymentIntent intent, ProviderEvent event, Instant now) {
        if (isStale(intent, event.occurredAt())) {
            return Judgement.refused(ProviderCallbackOutcome.IGNORED_STALE);
        }

        // SDD 12.3: a provider does not get to change what is owed. Recorded rather than applied,
        // with the claimed figure preserved in the stored payload so the disagreement is findable.
        if (claimsAnAmountTheIntentDoesNotAuthorize(intent, event)) {
            return Judgement.refused(ProviderCallbackOutcome.IGNORED_TERMINAL);
        }

        try {
            return Judgement.applied(transition(intent, event, now));
        } catch (ProviderOutcomeNotApplicableException notLegal) {
            // The aggregate refused. Caught rather than pre-checked so the state machine has exactly
            // one implementation and this service cannot reach a different conclusion from it. It is
            // safe to catch inside the transaction because the aggregate is pure Java -- nothing has
            // touched the database between the lock and here.
            return Judgement.refused(ProviderCallbackOutcome.IGNORED_TERMINAL);
        }
    }

    /**
     * THE MONOTONIC GUARD, AND THE COMPARISON IS STRICTLY AFTER.
     * <p>
     * <b>Ties are refused, and that is a choice between two failure modes rather than the
     * elimination of one</b> (ADR-012 section 3). Two genuinely different events sharing a timestamp
     * -- a provider emitting AUTHORIZED and SUCCEEDED in the same second -- means the second is
     * dropped and the payment strands in AUTHORIZED. Refusing a tie trades "moved backwards" for
     * "never moved forward". It is the better of the two, because a stranded payment is visible and
     * recoverable while a reversed one is neither, but it is not safety. The proper fix is a provider
     * sequence number instead of a timestamp.
     * <p>
     * Changing {@code isAfter} to "at or after" here is the sabotage the tie test exists to catch.
     */
    private boolean isStale(PaymentIntent intent, Instant occurredAt) {
        Optional<Instant> lastSeen =
            attempts.lastProviderEventAt(intent.merchantId(), intent.paymentIntentId());

        return lastSeen.isPresent() && !occurredAt.isAfter(lastSeen.get());
    }

    /**
     * The event's own arithmetic against the intent's.
     * <p>
     * FAILED and REQUIRES_ACTION carry no amount that means anything -- nothing was collected and
     * nothing was held -- so there is nothing to disagree with. A partial capture would need a
     * different rule and manual capture is the PR that owns it.
     */
    private static boolean claimsAnAmountTheIntentDoesNotAuthorize(
        PaymentIntent intent,
        ProviderEvent event
    ) {
        return switch (event.outcome()) {
            case SUCCEEDED -> !Long.valueOf(intent.amountMinor()).equals(event.capturedAmountMinor());
            case AUTHORIZED ->
                !Long.valueOf(intent.amountMinor()).equals(event.authorizedAmountMinor());
            case FAILED, REQUIRES_ACTION -> false;
        };
    }

    /** Pure. The aggregate decides whether the transition is legal and throws if it is not. */
    private static PaymentIntent transition(PaymentIntent intent, ProviderEvent event, Instant now) {
        return switch (event.outcome()) {
            case AUTHORIZED -> intent.authorize(now);
            case SUCCEEDED -> intent.succeed(event.capturedAmountMinor(), now);
            case FAILED -> intent.fail(event.failureCode(), event.failureMessage(), now);
            case REQUIRES_ACTION -> intent.requireAction(now);
        };
    }

    private void apply(
        PaymentIntent intent,
        PaymentIntent moved,
        RecordProviderCallbackCommand command,
        ProviderEvent event,
        Instant now
    ) {
        PaymentIntentStatus from = intent.status();

        // The attempt goes first, mirroring confirm: whatever fails, nothing is left claiming the
        // intent moved while the try that moved it still says PROCESSING.
        //
        // An APPLIED outcome means the intent was PROCESSING, and an intent is only PROCESSING
        // because a confirm opened an attempt -- so the absence of one is a corrupt database rather
        // than a case to handle.
        PaymentAttempt attempt = attempts
            .findLatest(intent.merchantId(), intent.paymentIntentId())
            .orElseThrow(() -> new IllegalStateException(
                "Payment intent " + intent.paymentIntentId().value()
                    + " is PROCESSING with no attempt to record a provider event against"
            ));

        attempts.save(attempt.recordProviderEvent(
            event.outcome().attemptStatus(),
            referenceToRecord(attempt, event),
            event.failureCode(),
            event.failureMessage(),
            // Stored REDACTED, exactly as the callback row is. SDD 12.6.
            event.redactedPayload(),
            event.occurredAt(),
            now
        ));

        PaymentIntent saved = paymentIntents.save(moved);

        history.append(new PaymentStateChange(
            saved.merchantId(),
            saved.paymentIntentId(),
            from,
            saved.status(),
            PaymentStateChange.ActorType.PROVIDER,
            command.provider(),
            saved.failureCode(),
            now
        ));

        outbox.append(announcement(saved, from, event, now));
    }

    /**
     * THE PROVIDER REFERENCE NAMES A PAYMENT, NOT A TRY, AND A 3DS CHALLENGE SPLITS ONE PAYMENT
     * ACROSS TWO TRIES.
     * <p>
     * {@code uq_payment_attempts_provider_reference} is unique on {@code (provider,
     * provider_reference)} across the whole table, which is what makes
     * {@link PaymentAttemptRepository#findByProviderReference} able to resolve a callback that
     * carries no intent id. But a provider keeps one reference for one payment: it issues the
     * reference, asks for 3DS, and reports the outcome on the same reference after the merchant
     * re-confirms -- and re-confirming opened a second attempt. Writing the reference onto that
     * second attempt violates the constraint.
     * <p>
     * The consequence was not a tidy error. The insert failed, the transaction rolled back, <b>and
     * the {@code provider_callbacks} row rolled back with it</b>, so the event was never deduplicated
     * and the provider retried into the same 500 forever. The intent stranded in PROCESSING, the
     * ADR-015 sweeper eventually recorded it {@code FAILED} for no response, that released the
     * order's slot, and the merchant could then collect a second time for money the customer had
     * already paid.
     * <p>
     * So the reference is recorded once per payment. If it is already held by another attempt of
     * <em>this</em> intent, this event is a later chapter of the same payment and the reference is
     * left where it is -- {@code recordProviderEvent} keeps the value already on the row it is
     * given, and the intent's reference is found through any of its attempts. If it is held by a
     * <em>different</em> intent, the provider has reused a reference across two payments, which is
     * not something to paper over: it fails loudly.
     */
    private String referenceToRecord(PaymentAttempt attempt, ProviderEvent event) {
        String reference = event.providerReference();

        if (reference == null || reference.isBlank()) {
            return reference;
        }

        Optional<PaymentAttempt> holder =
            attempts.findByProviderReference(attempt.provider(), reference);

        if (holder.isEmpty()) {
            return reference;
        }

        if (!holder.get().paymentIntentId().equals(attempt.paymentIntentId())) {
            throw new IllegalStateException(
                "Provider reference " + reference + " already names payment intent "
                    + holder.get().paymentIntentId().value()
                    + " and cannot be reused for " + attempt.paymentIntentId().value()
            );
        }

        // Already recorded against this payment, on this attempt or an earlier one. Returning null
        // leaves the existing value untouched rather than rewriting it.
        //
        // Deliberately not written as Optional.map(...).orElse(reference): a mapper that returns
        // null yields an empty Optional, so orElse would hand the reference straight back and the
        // guard would do nothing. That exact mistake was made here first and the 3DS test caught it.
        return null;
    }

    /**
     * SDD 22.1 names these four. The payload is the design's section 4.4 list plus the state the
     * intent came from, which every other payment event in this codebase also carries -- a consumer
     * reconciling a timeline cannot order two events without it.
     */
    private static OutboxEvent announcement(
        PaymentIntent intent,
        PaymentIntentStatus from,
        ProviderEvent event,
        Instant now
    ) {
        // HashMap, not Map.of: orderId is always present but capturedAmountMinor is zero rather than
        // absent, and a future optional field added to Map.of is a NullPointerException at runtime.
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", intent.paymentIntentId().value());
        payload.put("merchantId", intent.merchantId().value());
        payload.put("orderId", intent.orderId());
        payload.put("amountMinor", intent.amountMinor());
        payload.put("capturedAmountMinor", intent.capturedAmountMinor());
        payload.put("currency", intent.currency());
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        // THE PROVIDER'S CLOCK, in the payload only. A consumer needs to know when the provider says
        // it happened; the envelope's occurredAt below is when PayMesh recorded it, and a late
        // delivery makes those two genuinely different facts.
        payload.put("occurredAt", event.occurredAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            eventTypeFor(event.outcome()),
            PAYMENT_OUTCOME_VERSION,
            payload,
            now
        );
    }

    /** {@code payment.succeeded}, {@code payment.failed}, and so on -- SDD 22.1's names. */
    private static String eventTypeFor(ProviderOutcome outcome) {
        return "payment." + outcome.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves the intent the callback is about and holds its row still until this transaction ends.
     * <p>
     * By id when the provider names one, otherwise by the reference it gave the attempt -- which is
     * the normal case for a real provider, and which is why
     * {@code uq_payment_attempts_provider_reference} is a unique index rather than a plain one.
     * <p>
     * <b>The lock is taken before the callback row is inserted, deliberately.</b> It is what makes
     * two DIFFERENT callbacks for one intent serialize: the second waits, reads the winner's
     * committed state, and correctly judges itself stale or terminal. Optimistic control would
     * instead fail the loser at flush time and need an application-level retry to be correct.
     */
    private PaymentIntent lockIntentNamedBy(RecordProviderCallbackCommand command) {
        ProviderEvent event = command.event();

        if (event.paymentIntentId() != null && !event.paymentIntentId().isBlank()) {
            PaymentIntentId paymentIntentId = PaymentIntentId.from(event.paymentIntentId());

            return paymentIntents.findForProviderCallbackForUpdate(paymentIntentId)
                .orElseThrow(() -> new PaymentIntentNotFoundException(paymentIntentId));
        }

        return attempts.findByProviderReference(command.provider(), event.providerReference())
            .flatMap(attempt -> paymentIntents.findForProviderCallbackForUpdate(
                attempt.paymentIntentId()
            ))
            .orElseThrow(() -> new PaymentIntentNotFoundException(
                command.provider(), event.providerReference()
            ));
    }

    /**
     * The decision and, when it is APPLIED, the aggregate the decision produced.
     * <p>
     * They travel together because computing the moved intent IS how legality is decided -- the
     * aggregate throws rather than answering a predicate -- and recomputing it after the insert would
     * be a second chance to reach a different answer.
     */
    private record Judgement(ProviderCallbackOutcome outcome, PaymentIntent moved) {

        static Judgement applied(PaymentIntent moved) {
            return new Judgement(ProviderCallbackOutcome.APPLIED, moved);
        }

        static Judgement refused(ProviderCallbackOutcome outcome) {
            return new Judgement(outcome, null);
        }
    }
}
