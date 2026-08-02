package com.paymesh.simulator.application;

import com.paymesh.simulator.domain.OutboundCallback;
import com.paymesh.simulator.domain.OutboundCallbackStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Delivers the callbacks the provider intends to send. <b>The reason this module is worth
 * building.</b>
 *
 * <h2>Why this is a job and not a method call</h2>
 *
 * Every failure mode SDD 13 asks the simulator to reproduce -- delayed, lost, duplicated,
 * out-of-order, retried -- is a property of <em>when</em> and <em>how often</em> a callback is
 * delivered. An inline POST from the create handler can express none of them, because it happens
 * once, immediately, on the caller's thread, inside the caller's transaction. Queueing the intent
 * and delivering it separately is the only shape in which any of those words means anything.
 *
 * <h2>One transaction per callback</h2>
 *
 * Each row is taken with {@code SELECT ... FOR UPDATE SKIP LOCKED}, delivered, and its result
 * committed, on its own. One bad delivery therefore cannot roll back the results of the ones
 * delivered beside it, and a second dispatcher takes different rows rather than queueing behind this
 * one's HTTP call.
 * <p>
 * <b>Accepted cost, stated rather than hidden:</b> the HTTP call happens while the row lock is held,
 * so a hung receiver holds one lock and one connection for the client's read timeout. The correct
 * shape at scale is claim, send, then acknowledge -- which needs a third state and a lease expiry --
 * and that is machinery for a problem a simulator delivering to a local receiver does not have. The
 * mitigation is the short read timeout and the small batch. ADR-017.
 *
 * <h2>Delivery is at-least-once, and that is not a defect</h2>
 *
 * A callback can be POSTed, applied by PayMesh, and have its response lost before this row is marked
 * DELIVERED -- in which case it is redelivered. That is the platform-wide rule, it is what
 * {@code pk_provider_callbacks} makes safe on the other side, and a redelivery answering DUPLICATE
 * is the mechanism working rather than failing.
 *
 * <h2>No logic in the scheduled bean</h2>
 *
 * {@code SimulatorCallbackDispatcher} is a timer and one log line. Everything decidable lives here,
 * in an ordinary object taking an injected {@link Clock}, so every test drives {@link #dispatch()}
 * directly and none of them waits for a scheduler.
 */
public final class DispatchProviderCallbacksService {

    private static final Logger log = LoggerFactory.getLogger(DispatchProviderCallbacksService.class);

    private final OutboundCallbackRepository callbacks;
    private final CallbackSender sender;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration retryDelay;

    public DispatchProviderCallbacksService(
        OutboundCallbackRepository callbacks,
        CallbackSender sender,
        TransactionTemplate transactions,
        Clock clock,
        int batchSize,
        int maxAttempts,
        Duration retryDelay
    ) {
        this.callbacks = callbacks;
        this.sender = sender;
        this.transactions = transactions;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    /**
     * One pass over the due callbacks.
     * <p>
     * The candidate list is read in its own transaction and each row is then re-read under its own
     * lock, rather than the whole batch being locked for the duration of every HTTP call in it. The
     * cost is one extra read per row; the benefit is that a batch of twenty does not hold twenty
     * locks while the first one waits on a socket.
     */
    public DispatchResult dispatch() {
        Instant now = Instant.now(clock);
        List<String> due = callbacks.findDue(now, batchSize).stream()
            .map(OutboundCallback::outboundCallbackId)
            .toList();

        int delivered = 0;
        int retried = 0;
        int abandoned = 0;

        for (String callbackId : due) {
            switch (deliverOne(callbackId)) {
                case DELIVERED -> delivered++;
                case RETRIED -> retried++;
                case ABANDONED -> abandoned++;
                case GONE -> {
                    // Taken by a concurrent dispatcher between the candidate read and the lock. Not
                    // an error: SKIP LOCKED exists so that is a no-op rather than a wait.
                }
            }
        }

        return new DispatchResult(due.size(), delivered, retried, abandoned);
    }

    private Attempt deliverOne(String callbackId) {
        return transactions.execute(status -> {
            Instant now = Instant.now(clock);

            OutboundCallback callback = callbacks.findPendingForUpdate(callbackId).orElse(null);

            if (callback == null) {
                return Attempt.GONE;
            }

            // THE STORED BYTES, UNTOUCHED. The sender signs exactly this string and posts exactly
            // this string; nothing re-serializes it, which is the only reason the signature can be
            // trusted to cover what is on the wire.
            CallbackDelivery delivery = sender.send(callback.body());

            if (delivery.accepted()) {
                callbacks.save(callback.delivered(
                    delivery.statusCode(), delivery.outcome(), now
                ));

                return Attempt.DELIVERED;
            }

            OutboundCallback failed = callback.failed(
                delivery.statusCode(), maxAttempts, now.plus(retryDelay), now
            );

            callbacks.save(failed);

            if (failed.status() == OutboundCallbackStatus.ABANDONED) {
                log.warn(
                    "Abandoned provider callback {} for {} after {} attempts, last status {}",
                    failed.outboundCallbackId(), failed.callbackReference(), failed.attempts(),
                    delivery.statusCode()
                );

                return Attempt.ABANDONED;
            }

            return Attempt.RETRIED;
        });
    }

    private enum Attempt {
        DELIVERED,
        RETRIED,
        ABANDONED,
        GONE
    }

    /** What one pass did. Counted so the scheduled bean can log something worth reading. */
    public record DispatchResult(int examined, int delivered, int retried, int abandoned) {
    }
}
