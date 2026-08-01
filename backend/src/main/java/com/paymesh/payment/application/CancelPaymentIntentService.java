package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class CancelPaymentIntentService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_CANCELLED_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CancelPaymentIntentService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        GetPaymentIntentService getPaymentIntentService,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.history = history;
        this.getPaymentIntentService = getPaymentIntentService;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Requests cancellation. The service does not decide whether it is allowed -- it loads the
     * aggregate and asks, so the state machine has exactly one implementation and a second caller
     * cannot reach a different conclusion.
     * <p>
     * Cancelling is also what releases the order's live-intent slot (ADR-011), so this is the path a
     * merchant takes to abandon a collection and start another.
     */
    public PaymentIntent cancel(MerchantId merchantId, PaymentIntentId paymentIntentId, String reason) {
        return cancel(
            merchantId,
            paymentIntentId,
            reason,
            PaymentStateChange.ActorType.MERCHANT,
            merchantId.value()
        );
    }

    /**
     * The same transition, attributed to whoever actually caused it.
     * <p>
     * The sweeper that cancels abandoned checkouts is not a merchant and its timeline rows must not
     * claim to be. Everything else is identical on purpose: one transaction, one lock, one state
     * machine, one event. A second copy of this block for system-initiated cancellation is how the
     * two drift into disagreeing about what cancelling means.
     */
    public PaymentIntent cancel(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        String reason,
        PaymentStateChange.ActorType actorType,
        String actorId
    ) {
        Instant now = Instant.now(clock);

        // The transition, its history row and the event announcing it commit together or not at all,
        // for the same reason creation's three writes do: a timeline missing a transition that
        // happened is worse than no timeline, because it looks complete.
        //
        // THE READ IS INSIDE THE TRANSACTION AND HOLDS A ROW LOCK. It used to be outside, and that
        // was a race with itself and with confirm: two writers deciding from the same row collide on
        // the optimistic version, and the loser gets a 500 about row counts rather than the 409 the
        // state machine would have given it. Under the lock the loser waits and is told what
        // actually happened.
        return transactions.execute(status -> {
            PaymentIntent intent = getPaymentIntentService.getByIdForUpdate(
                merchantId, paymentIntentId
            );
            PaymentIntentStatus from = intent.status();
            PaymentIntent saved = paymentIntents.save(intent.cancel(reason, now));

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                from,
                saved.status(),
                actorType,
                actorId,
                saved.cancellationReason(),
                now
            ));

            outbox.append(paymentCancelled(saved, from, now));

            return saved;
        });
    }

    /**
     * THE DESIGN SPEC NAMES ONLY {@code payment.created} (section 2.4), AND ON REVIEW THAT LOOKS
     * LIKE A GAP RATHER THAN A DECISION.
     * <p>
     * Cancelling is the transition that releases the order's live-intent slot (ADR-011), so a
     * consumer fed only {@code payment.created} would hold a permanently live intent in its read
     * model and never learn otherwise -- and the first consumer anyone writes is the one that would
     * have to discover that. This is the same argument section 2.1 used to ship
     * {@code payment_state_history} in V8 rather than at PR 4: a stream with a hole in it is worse
     * than no stream, because it looks complete. The cancel transaction already existed and already
     * had two writes, so the third is nearly free now and awkward once a relay is running.
     * <p>
     * Emitted here deliberately, and recorded as a correction to the spec rather than a silent
     * divergence from it.
     */
    private static OutboxEvent paymentCancelled(
        PaymentIntent intent,
        PaymentIntentStatus from,
        Instant occurredAt
    ) {
        // HashMap, not Map.of: the customer and the reason are both legitimately absent and Map.of
        // rejects a null value. They are carried as explicit JSON nulls rather than dropped, so a
        // consumer reads the same shape for every cancellation.
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", intent.paymentIntentId().value());
        payload.put("merchantId", intent.merchantId().value());
        payload.put("orderId", intent.orderId());
        payload.put("customerId", intent.customerId());
        payload.put("amountMinor", intent.amountMinor());
        payload.put("currency", intent.currency());
        // The state it came from. A consumer reconciling a timeline needs to know whether the intent
        // died before or after a payment method was ever attached, and only this event says so.
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        payload.put("cancellationReason", intent.cancellationReason());
        payload.put("cancelledAt", intent.cancelledAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.cancelled",
            PAYMENT_CANCELLED_VERSION,
            payload,
            occurredAt
        );
    }
}
