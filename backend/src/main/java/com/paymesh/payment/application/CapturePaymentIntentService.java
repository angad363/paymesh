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

/**
 * Manual capture: the merchant collects an authorization the provider is holding (design spec
 * section 5).
 * <p>
 * This completes the intent state machine. After it, every status in {@code PaymentIntentStatus} is
 * reachable except PARTIALLY_REFUNDED and REFUNDED, which belong to the Refund capability and must
 * stay unreachable -- there is no path to either in this class or anywhere else in the module.
 * <p>
 * <b>Capturing moves no balance and posts no ledger entry</b> (design spec section 0.5). SUCCEEDED
 * here is operational state saying the merchant asked for the funds; the Ledger, when it exists,
 * remains the financial source of truth.
 */
public final class CapturePaymentIntentService {

    /**
     * SDD 22.1. The same event name the provider path emits, and deliberately so: a consumer cares
     * that a payment succeeded and for how much, not which of two authorities said so. The
     * {@code previousStatus} in the payload distinguishes them (PROCESSING for a provider outcome,
     * AUTHORIZED for a capture), which is the fact a reconciler actually needs.
     */
    private static final int PAYMENT_SUCCEEDED_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;
    private final OrderLookup orders;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CapturePaymentIntentService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        GetPaymentIntentService getPaymentIntentService,
        OrderLookup orders,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.history = history;
        this.getPaymentIntentService = getPaymentIntentService;
        this.orders = orders;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Captures, in full unless a smaller amount is named.
     *
     * @param requestedAmountMinor null means the whole authorized amount. Resolved HERE rather than
     *                             defaulted in the request record, because the default is a fact
     *                             about the intent -- only the aggregate knows what was authorized --
     *                             and a request record cannot see it.
     */
    public PaymentIntent capture(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        Long requestedAmountMinor
    ) {
        Instant now = Instant.now(clock);

        // ONE TRANSACTION, THREE WRITES (ADR-010): the intent, its timeline row and the event. A
        // capture that committed without its event would tell a consumer the intent is still merely
        // authorized, forever.
        return transactions.execute(status -> {
            // LOCKED, like every other transition (PRs 3 and 4). Two concurrent captures would
            // otherwise both read AUTHORIZED, both decide they may collect, and collide on the
            // intent's optimistic version -- handing the loser a 500 about row counts instead of the
            // 409 the state machine gives it once it can see the winner's committed state.
            PaymentIntent intent = getPaymentIntentService.getByIdForUpdate(
                merchantId, paymentIntentId
            );

            PaymentIntentStatus from = intent.status();
            long amount = requestedAmountMinor == null ? intent.amountMinor() : requestedAmountMinor;

            // THE AGGREGATE DECIDES FIRST, AND THE ORDERING OF THESE TWO CHECKS IS DELIBERATE.
            // capture() is pure -- nothing is written until the save below -- so asking it first
            // costs nothing and buys the better answer: a merchant capturing an intent that is
            // already SUCCEEDED, or one that captures automatically, learns THAT rather than being
            // told their order is not payable. The payability guard is about a live capture going
            // through against a dead order; it should not be answering questions about the intent.
            PaymentIntent captured = intent.capture(amount, now);

            requireStillPayable(merchantId, intent.orderId());

            PaymentIntent saved = paymentIntents.save(captured);

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                from,
                saved.status(),
                // MERCHANT, not PROVIDER. The provider authorized; the merchant decided to collect.
                PaymentStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                null,
                now
            ));

            outbox.append(paymentSucceeded(saved, from, now));

            return saved;
        });
    }

    /**
     * ADR-013'S GUARD, APPLIED TO THE OTHER MONEY-MOVING TRANSITION.
     * <p>
     * ADR-013 put this on confirm because confirm was the moment money started moving. On a
     * MANUAL-capture intent it is not: confirm only obtains an authorization, and <b>capture is where
     * the funds are actually taken.</b> An order cancelled -- or expired (ADR-014) -- between the two
     * would otherwise be collected against, which is precisely the outcome ADR-013 exists to prevent.
     * Leaving the check off here would have closed the hole on one path and reopened it on the other.
     * <p>
     * A plain read rather than the locking one, for the reason ADR-013 section 2 gives: locking the
     * order here would move the arbitration of two concurrent captures off the intent's row lock,
     * which refuses the loser with a 409 naming the real situation.
     * <p>
     * <b>The residue, stated rather than discovered later:</b> an AUTHORIZED intent whose order is
     * cancelled can no longer be captured, and the funds the provider is holding must be released by
     * cancelling the intent. That route exists -- AUTHORIZED is cancellable from this PR -- and it is
     * the correct outcome: a merchant who cancels an order has said they do not want the money.
     */
    private void requireStillPayable(MerchantId merchantId, String orderId) {
        orders.find(merchantId, orderId)
            .filter(OrderLookup.PayableOrder::payable)
            .orElseThrow(() -> new OrderNotPayableException(orderId));
    }

    /**
     * SDD 22.1 names this event.
     *
     * <h2>ONE SHAPE, AND IT USED NOT TO BE (ADR-016 section 6)</h2>
     *
     * This payload and {@code RecordProviderCallbackService}'s were different at the same envelope
     * version 1: this one carried {@code customerId}, {@code captureMethod} and {@code capturedAt},
     * that one carried {@code occurredAt} and none of the other three. A consumer reading
     * {@code customerId} would have got a value from one authority and null from the other, with
     * nothing in the envelope to tell it which it was holding. It never bit because nothing had ever
     * read an event; the relay is what makes it a live defect, so it is fixed in the same change.
     * <p>
     * {@code capturedAt} is gone and {@code occurredAt} carries its value. On this path they were the
     * same {@link Instant} by construction, so no data is lost, and the one key now means the same
     * thing on both paths: <b>when the authority that decided this says it happened</b> -- the
     * provider's clock over there, the capture instant here. The envelope's own {@code occurredAt}
     * continues to mean when PayMesh recorded it.
     * <p>
     * The version stays 1 deliberately. Bumping it would claim there is a v1 consumer to protect and
     * there has never been one -- no event had ever been delivered to anything.
     */
    private static OutboxEvent paymentSucceeded(
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
        // THE FIGURE THAT MAKES orders.PARTIALLY_PAID REACHABLE, and a consumer now reads it: Order's
        // PaymentSucceededHandler compares it against the ORDER's amount. It is below amountMinor on
        // a partial capture, and a consumer that assumed the two were always equal would mark such an
        // order fully PAID.
        payload.put("capturedAmountMinor", intent.capturedAmountMinor());
        payload.put("currency", intent.currency());
        payload.put("captureMethod", intent.captureMethod().name());
        // AUTHORIZED here, PROCESSING on the provider path. This is how a consumer tells a merchant
        // capture from an automatic one without a second event type.
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        payload.put("occurredAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.succeeded",
            PAYMENT_SUCCEEDED_VERSION,
            payload,
            occurredAt
        );
    }
}
