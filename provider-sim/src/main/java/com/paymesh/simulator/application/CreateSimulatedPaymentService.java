package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.CallbackBody;
import com.paymesh.simulator.domain.FailureProfile;
import com.paymesh.simulator.domain.OutboundCallback;
import com.paymesh.simulator.domain.SimulatedBehaviour;
import com.paymesh.simulator.domain.SimulatedPayment;
import com.paymesh.simulator.domain.SimulatedPaymentId;
import com.paymesh.simulator.domain.SimulatedPaymentStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Takes a payment and queues whatever the provider intends to say about it.
 *
 * <h2>One transaction, both halves</h2>
 *
 * The {@code provider_payments} row and its {@code provider_outbound_callbacks} rows commit
 * together. Either the provider took the payment and intends to report it, or neither happened. A
 * payment committed without its callback would be a permanently silent provider -- the same
 * stranded-in-PROCESSING failure the simulator exists to be able to <em>choose</em>, arriving by
 * accident instead.
 *
 * <h2>The callback is queued, never sent from here</h2>
 *
 * An inline POST would be shorter and would make the module worthless: delayed, lost, duplicated and
 * out-of-order deliveries are all properties of when and how often a callback goes, and an inline
 * call has no opinion about either. See {@link OutboundCallback}.
 */
public final class CreateSimulatedPaymentService {

    /**
     * How far back the out-of-order pair's second event is stamped.
     * <p>
     * Any strictly negative offset works -- PayMesh refuses anything not strictly after
     * {@code last_provider_event_at} -- but a minute is large enough to be unmistakable in a stored
     * payload that a human is reading during an investigation.
     */
    private static final Duration STALE_SKEW = Duration.ofSeconds(60);

    /**
     * The gap between the two rows of a duplicate or out-of-order pair.
     * <p>
     * The dispatcher orders due rows by {@code deliver_after}, so without a gap the two would be
     * delivered in whatever order the database happened to return them and the scenario would be a
     * coin flip. One millisecond is also honest: a provider re-sending an event does it a moment
     * later, not simultaneously.
     */
    private static final Duration PAIR_GAP = Duration.ofMillis(1);

    private final SimulatedPaymentRepository payments;
    private final OutboundCallbackRepository callbacks;
    private final FailureProfileRepository profiles;
    private final CallbackBodyWriter bodyWriter;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreateSimulatedPaymentService(
        SimulatedPaymentRepository payments,
        OutboundCallbackRepository callbacks,
        FailureProfileRepository profiles,
        CallbackBodyWriter bodyWriter,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.payments = payments;
        this.callbacks = callbacks;
        this.profiles = profiles;
        this.bodyWriter = bodyWriter;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * @throws IdempotencyKeyReusedException when the key is known and the request differs
     */
    public SimulatedPaymentResult create(CreateSimulatedPaymentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create Simulated Payment Command cannot be null");
        }

        String requestHash = hashOf(command);
        SimulatedPaymentResult replay = replayFor(command.idempotencyKey(), requestHash);

        if (replay != null) {
            return replay;
        }

        try {
            return transactions.execute(status -> takePayment(command, requestHash));
        } catch (IdempotencyKeyRaceLostException lost) {
            // THE CONSTRAINT PICKED THE WINNER, WHICH IS THE POINT. This transaction is already
            // rolled back, so the re-read has to happen in a fresh one -- and the winner's row is a
            // complete answer, so the loser returns it and its caller cannot tell which it was.
            SimulatedPaymentResult winner = replayFor(lost.idempotencyKey(), requestHash);

            if (winner == null) {
                throw new IllegalStateException(
                    "Idempotency key " + lost.idempotencyKey()
                        + " collided but the winning payment could not be read back",
                    lost
                );
            }

            return winner;
        }
    }

    /**
     * The friendly half of provider-side idempotency (SDD 13.1).
     * <p>
     * <b>{@code uq_provider_payments_idempotency_key} is the guard; this look-up only buys a nicer
     * answer.</b> The integration test races two creates on one key and asserts exactly one row,
     * which is a test of the constraint and not of this method -- deleting this method entirely
     * leaves that test green.
     */
    private SimulatedPaymentResult replayFor(String idempotencyKey, String requestHash) {
        return payments.findByIdempotencyKey(idempotencyKey)
            .map(existing -> {
                if (!existing.requestHash().equals(requestHash)) {
                    throw new IdempotencyKeyReusedException(idempotencyKey);
                }

                return SimulatedPaymentResult.replayed(existing);
            })
            .orElse(null);
    }

    private SimulatedPaymentResult takePayment(
        CreateSimulatedPaymentCommand command,
        String requestHash
    ) {
        // ONE READING OF THE CLOCK for the whole request: the payment's timestamps, every callback's
        // occurred_at and every deliver_after describe the same moment, and separate reads would put
        // gaps between facts that are simultaneous by definition.
        Instant now = Instant.now(clock);
        FailureProfile profile = profiles.get();

        SimulatedPayment payment = payments.save(SimulatedPayment.authorize(
            SimulatedPaymentId.generate(),
            command.idempotencyKey(),
            requestHash,
            command.callbackReference(),
            command.method(),
            command.token(),
            SimulatedBehaviour.resolve(command.token(), profile.defaultBehaviour()),
            command.amountMinor(),
            command.currency(),
            command.captureMethod(),
            now
        ));

        for (OutboundCallback callback : plan(payment, profile, now)) {
            callbacks.save(callback);
        }

        return SimulatedPaymentResult.created(payment);
    }

    /**
     * THE FAILURE-INJECTION TABLE, and the only place it is written down.
     * <p>
     * Every row of it is a shape the receiving side has a named answer for, so a change here that
     * breaks the contract shows up as a changed {@code last_response_outcome} rather than as
     * silence.
     */
    private List<OutboundCallback> plan(
        SimulatedPayment payment,
        FailureProfile profile,
        Instant now
    ) {
        Instant deliverAfter = now.plus(profile.callbackDelay());

        return switch (payment.behaviour()) {
            // THE LOST CALLBACK. Nothing is queued, so nothing is ever delivered, and the PayMesh
            // intent stays in PROCESSING with no local exit -- the exact state ADR-015's timeout
            // sweeper exists for and which nothing could reach before this module.
            case TIMEOUT -> List.of();

            case DECLINE -> List.of(queue(
                CallbackBody.failed(
                    OutboundCallback.newEventId(),
                    payment.callbackReference(),
                    payment.providerPaymentId(),
                    SimulatedPayment.DECLINE_CODE,
                    SimulatedPayment.DECLINE_MESSAGE,
                    now
                ),
                payment, deliverAfter, now
            ));

            case REQUIRE_ACTION -> List.of(queue(
                CallbackBody.requiresAction(
                    OutboundCallback.newEventId(),
                    payment.callbackReference(),
                    payment.providerPaymentId(),
                    now
                ),
                payment, deliverAfter, now
            ));

            // THE DUPLICATE. Two rows, ONE event id, and the identical body string -- which is what
            // a provider re-sending an event it is unsure landed actually does. PayMesh answers
            // APPLIED then DUPLICATE and applies the payment once (ADR-012 section 1).
            case DUPLICATE_CALLBACK -> {
                CallbackBody body = succeededBody(payment, now);

                yield List.of(
                    queue(body, payment, deliverAfter, now),
                    queue(body, payment, deliverAfter.plus(PAIR_GAP), now)
                );
            }

            // THE OUT-OF-ORDER PAIR. Distinct event ids -- these are two DIFFERENT events, not one
            // re-sent -- and the second carries an EARLIER occurred_at, as a provider whose delivery
            // queue reordered would.
            //
            // PayMesh judges staleness BEFORE the state machine, so the second is IGNORED_STALE
            // rather than IGNORED_TERMINAL. That ordering is why this can be reproduced from outside
            // with no merchant action in between.
            case STALE_CALLBACK -> {
                CallbackBody first = succeededBody(payment, now);

                yield List.of(
                    queue(first, payment, deliverAfter, now),
                    queue(
                        first.at(OutboundCallback.newEventId(), now.minus(STALE_SKEW)),
                        payment, deliverAfter.plus(PAIR_GAP), now
                    )
                );
            }

            // MANUAL stops at AUTHORIZED and waits to be captured. A provider may not capture on its
            // own say-so -- ADR-012 section 4 refuses AUTHORIZED to SUCCEEDED from a callback -- so
            // sending SUCCEEDED here would earn an IGNORED_TERMINAL and look like a broken simulator.
            case SUCCEED -> List.of(queue(
                payment.status() == SimulatedPaymentStatus.AUTHORIZED
                    ? CallbackBody.authorized(
                        OutboundCallback.newEventId(),
                        payment.callbackReference(),
                        payment.providerPaymentId(),
                        payment.amountMinor(),
                        now
                    )
                    : succeededBody(payment, now),
                payment, deliverAfter, now
            ));
        };
    }

    private static CallbackBody succeededBody(SimulatedPayment payment, Instant now) {
        return CallbackBody.succeeded(
            OutboundCallback.newEventId(),
            payment.callbackReference(),
            payment.providerPaymentId(),
            payment.amountMinor(),
            now
        );
    }

    /**
     * Serializes the body ONCE and hands the string to the row.
     * <p>
     * Everything downstream signs and posts that stored string verbatim. There is no second
     * serialization for the signed bytes and the sent bytes to drift across, which is the only
     * reason the HMAC can be trusted to cover what is actually on the wire.
     */
    private OutboundCallback queue(
        CallbackBody body,
        SimulatedPayment payment,
        Instant deliverAfter,
        Instant now
    ) {
        return OutboundCallback.enqueue(
            body.eventId(),
            payment.providerPaymentId(),
            payment.callbackReference(),
            body.outcome(),
            body.occurredAt(),
            deliverAfter,
            bodyWriter.write(body),
            now
        );
    }

    private static String hashOf(CreateSimulatedPaymentCommand command) {
        return RequestHashes.of(
            command.callbackReference(),
            command.method(),
            command.token(),
            command.amountMinor(),
            command.currency(),
            command.captureMethod()
        );
    }
}
