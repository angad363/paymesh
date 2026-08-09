package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentAttemptId;
import com.paymesh.payment.domain.PaymentIntent;
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

public final class ConfirmPaymentIntentService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_PROCESSING_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentAttemptRepository attempts;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;
    private final OrderLookup orders;
    private final RiskCheck risk;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ConfirmPaymentIntentService(
        PaymentIntentRepository paymentIntents,
        PaymentAttemptRepository attempts,
        PaymentStateHistoryRepository history,
        GetPaymentIntentService getPaymentIntentService,
        OrderLookup orders,
        RiskCheck risk,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.attempts = attempts;
        this.history = history;
        this.getPaymentIntentService = getPaymentIntentService;
        this.orders = orders;
        this.risk = risk;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Starts collecting: the intent moves to PROCESSING and an attempt is opened against it.
     * <p>
     * <b>NOTHING IS CALLED.</b> There is no provider in PayMesh yet, so confirm writes four rows and
     * stops; the outcome stays undecided until the callback endpoint resolves it, which is why the
     * endpoint answers 202 and why PROCESSING has no local cancel. That is not a gap in the design.
     * It is what an asynchronous payment looks like with the provider not built.
     * <p>
     * <b>There IS a risk decision now</b> (SDD §12.2, §14, ADR-030), and it sits in the seam this
     * javadoc used to describe as empty: between loading the intent and opening the attempt. It is
     * the only thing here that can refuse a confirm for a reason the state machine does not own.
     */
    public PaymentIntent confirm(ConfirmPaymentIntentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Confirm Payment Intent Command cannot be null");
        }

        Instant now = Instant.now(clock);

        // ONE TRANSACTION, AND THE READS ARE INSIDE IT (ADR-010, ADR-013). Four writes -- the
        // attempt, the intent, its timeline row and the event -- plus two decisions that must be
        // made against state that is still true when the writes land. A confirm that read the
        // intent's status or the order's payability outside this block would be deciding on a
        // snapshot and writing to a world that had moved on.
        return transactions.execute(status -> {
            // LOCKED, NOT MERELY READ. A double-clicked confirm is two transactions deciding from
            // the same row; without the lock they collide on the intent's optimistic version and the
            // loser gets a 500 about row counts. With it the loser waits, reads PROCESSING, and is
            // refused by the state machine with an answer that says so.
            PaymentIntent intent = getPaymentIntentService.getByIdForUpdate(
                command.merchantId(), command.paymentIntentId()
            );

            requireStillPayable(command.merchantId(), intent.orderId());

            // RISK RUNS HERE: after the lock, after payability, before the status moves.
            //
            // After the lock, so the evaluation and the confirm are one decision -- there is no
            // window where a payment was refused but nothing recorded it, or recorded as allowed
            // and then never confirmed. Before the status moves, because a blocked payment must
            // leave the intent exactly as it found it (see PaymentBlockedByRiskException).
            //
            // SDD 14.2: Risk returns a decision, Payment acts on it. The acting is here and only
            // here -- Risk writes nothing to any payment table.
            requireAcceptableRisk(intent, command.device());

            PaymentIntentStatus from = intent.status();
            PaymentIntent confirmed = intent.confirm(now);

            // The attempt goes in before the intent moves. The row lock above is what actually
            // serializes two confirms, so uq_payment_attempts_intent_number is the backstop rather
            // than the arbiter -- but a backstop that fires before the intent has been declared
            // PROCESSING leaves nothing half-done to explain.
            attempts.append(PaymentAttempt.start(
                PaymentAttemptId.generate(),
                confirmed,
                attempts.nextAttemptNumber(confirmed.merchantId(), confirmed.paymentIntentId()),
                command.returnUrl(),
                command.device(),
                now
            ));

            PaymentIntent saved = paymentIntents.save(confirmed);

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                from,
                saved.status(),
                PaymentStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                null,
                now
            ));

            outbox.append(paymentProcessing(saved, from, now));

            return saved;
        });
    }

    /**
     * THE GUARD THIS PR EXISTS TO PLACE (ADR-013, and open item 3 of the design's section 7).
     * <p>
     * An order can be cancelled out from under a live intent. {@code Order.cancel} checks only that
     * the order is PENDING, and it must stay that way -- Order is not allowed to know Payment exists
     * (design section 0.5) -- so nothing on Order's side can refuse it. The intent created against
     * that order is still live, still holds its slot, and before this check was still confirmable.
     * <b>PayMesh would have collected for an order the merchant explicitly cancelled.</b>
     * <p>
     * Payability is therefore re-read here, inside the transaction, through the same port create
     * uses.
     * <p>
     * A PLAIN READ RATHER THAN THE LOCKING ONE, AND THAT IS A CHOICE WITH A COST (ADR-013 section
     * 2). Locking the order here would also close the last few milliseconds -- a cancel committing
     * between this read and this transaction's commit -- but it would move the arbitration of two
     * concurrent confirms off {@code uq_payment_attempts_intent_number}, which refuses the loser
     * with a 409 naming the actual problem, and onto the intent's optimistic version, which refuses
     * it with a 500. Trading a clean answer for the common case against a narrower window on a rare
     * one is the wrong way round, and the window is bounded by the fact that no money moves in this
     * design at all.
     * <p>
     * The answer is {@code ORDER_NOT_PAYABLE}, the same one code for the same three causes create
     * uses: no such order, not this merchant's, not in a payable state. Distinguishing them here
     * would reopen the enumeration oracle that {@link OrderNotPayableException} was written to
     * close.
     */
    /**
     * Asks Risk, and refuses the confirm if Risk says no.
     * <p>
     * The evaluation is recorded whatever it says, so an allowed payment leaves evidence too --
     * "we looked and here is the record" is worth as much to a support conversation as the refusal.
     * <p>
     * <b>A failure here fails the confirm rather than defaulting to allow.</b> That is the fail
     * CLOSED choice and it is deliberate: this evaluation is two indexed queries against the same
     * database inside the same transaction, so if it cannot run then the transaction is already
     * lost and confirming anyway would only mean confirming into a database that is not answering.
     * A fail-open default would matter if Risk were a network call to somewhere else, and if it
     * ever becomes one, this line is the one to revisit.
     */
    private void requireAcceptableRisk(PaymentIntent intent, String device) {
        RiskCheck.Decision decision = risk.evaluate(
            intent.merchantId(),
            intent.paymentIntentId().value(),
            intent.amountMinor(),
            intent.currency(),
            intent.customerId(),
            device
        );

        if (!decision.permitted()) {
            throw new PaymentBlockedByRiskException(decision.assessmentId());
        }
    }

    private void requireStillPayable(MerchantId merchantId, String orderId) {
        orders.find(merchantId, orderId)
            .filter(OrderLookup.PayableOrder::payable)
            .orElseThrow(() -> new OrderNotPayableException(orderId));
    }

    /** SDD 22.1 names this event. It is the one a provider-facing consumer waits for. */
    private static OutboxEvent paymentProcessing(
        PaymentIntent intent,
        PaymentIntentStatus from,
        Instant occurredAt
    ) {
        // HashMap, not Map.of: customerId is legitimately absent on a guest checkout and Map.of
        // rejects a null value. It is carried as an explicit JSON null rather than dropped, so a
        // consumer reads the same shape for every intent.
        Map<String, Object> payload = new HashMap<>();
        payload.put("paymentIntentId", intent.paymentIntentId().value());
        payload.put("merchantId", intent.merchantId().value());
        payload.put("orderId", intent.orderId());
        payload.put("customerId", intent.customerId());
        payload.put("amountMinor", intent.amountMinor());
        payload.put("currency", intent.currency());
        payload.put("captureMethod", intent.captureMethod().name());
        payload.put("paymentMethodType", intent.paymentMethodType().name());
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        payload.put("confirmedAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.processing",
            PAYMENT_PROCESSING_VERSION,
            payload,
            occurredAt
        );
    }
}
