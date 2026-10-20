package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.application.OutboxWriter;
import com.paymesh.shared.outbox.domain.EventId;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class CreatePaymentIntentService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_CREATED_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final OrderLookup orders;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreatePaymentIntentService(
        PaymentIntentRepository paymentIntents,
        PaymentStateHistoryRepository history,
        OrderLookup orders,
        OutboxWriter outbox,
        TransactionTemplate transactions,
        Clock clock
    ) {
        this.paymentIntents = paymentIntents;
        this.history = history;
        this.orders = orders;
        this.outbox = outbox;
        this.transactions = transactions;
        this.clock = clock;
    }

    public PaymentIntent create(CreatePaymentIntentCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Create Payment Intent Command cannot be null");
        }

        String orderId = trimToNull(command.orderId());

        if (orderId == null) {
            throw new IllegalArgumentException("Order identifier is required");
        }

        Instant now = Instant.now(clock);

        // ONE TRANSACTION, THREE WRITES, AND THAT IS THE POINT (ADR-010). The intent, the first row
        // of its timeline and the event announcing it are one fact. An intent whose creation is
        // missing from payment_state_history has a hole in an audit trail that exists only to have
        // no holes, and an intent committed without its event is invisible to every consumer
        // forever.
        //
        // THE ORDER IS READ INSIDE IT, AND WITH A LOCK (ADR-013). Payability used to be read out
        // here, which made it a time-of-check to time-of-use race: an order cancelled between the
        // lookup and the insert left a live intent against a cancelled order, holding the order's
        // only slot with no route back through the API. The FOR UPDATE read holds the row still
        // until this transaction ends, so a concurrent cancel waits and then loses -- or commits
        // first and is seen.
        //
        // Forgetting this wrap compiles and passes every happy-path test -- the accepted cost of
        // TransactionTemplate over @Transactional, which cannot be used on a final class.
        return transactions.execute(status -> {
            // No such order, another merchant's order, and an order that cannot be paid are one
            // answer. Distinguishing them would confirm which ids exist under another tenant.
            OrderLookup.PayableOrder order = orders
                .findForUpdate(command.merchantId(), orderId)
                .filter(OrderLookup.PayableOrder::payable)
                .orElseThrow(() -> new OrderNotPayableException(orderId));

            requireExactAmount(command, order);

            // Everything the intent needs about the order is resolved BEFORE the aggregate is
            // built, so there is exactly one PaymentIntent.create call and no second pass to
            // correct a field.
            PaymentIntent intent = PaymentIntent.create(
                PaymentIntentId.generate(),
                command.merchantId(),
                orderId,
                requireCustomerMatchesOrder(command, order),
                command.amountMinor(),
                command.currency(),
                command.captureMethod(),
                command.description(),
                command.metadata(),
                now
            );

            // A CHECK, NOT A LOCK. Two concurrent creates can both pass it; the partial unique
            // index uq_payment_intents_live_per_order is what makes the second one lose, and the
            // adapter translates its violation into this same exception. This call only buys a
            // friendlier message on the common, uncontended path -- deleting it must leave every
            // test green.
            if (paymentIntents.existsLiveForOrder(command.merchantId(), orderId)) {
                throw new OrderHasActivePaymentIntentException(orderId);
            }

            PaymentIntent saved = paymentIntents.save(intent);

            history.append(new PaymentStateChange(
                saved.merchantId(),
                saved.paymentIntentId(),
                // Null, not the initial status repeated: an intent that has just been created came
                // from nowhere, and naming a from-status would claim a transition that never
                // happened.
                null,
                saved.status(),
                PaymentStateChange.ActorType.MERCHANT,
                saved.merchantId().value(),
                null,
                now
            ));

            outbox.append(paymentCreated(saved, now));

            return saved;
        });
    }

    /**
     * The v1 narrowing that makes overpayment structurally impossible rather than merely
     * CHECK-constrained: an intent collects its order's exact obligation, and an order holds only
     * one live intent. Split payments are out of scope as a direct consequence.
     */
    private static void requireExactAmount(
        CreatePaymentIntentCommand command,
        OrderLookup.PayableOrder order
    ) {
        String currency = command.currency() == null
            ? null
            : command.currency().trim().toUpperCase(Locale.ROOT);

        if (command.amountMinor() != order.amountMinor()
            || !order.currency().equals(currency)) {
            throw new PaymentAmountMismatchException(
                command.amountMinor(), command.currency(), order.amountMinor(), order.currency()
            );
        }
    }

    /**
     * The customer is the order's, always. A caller may name one, but only the one the order
     * already carries -- naming a different customer is a contradiction rather than a preference,
     * and {@code fk_payment_intents_customer} would refuse it as a 500 instead of explaining it.
     * <p>
     * A guest order has no customer and neither does its intent.
     */
    private static String requireCustomerMatchesOrder(
        CreatePaymentIntentCommand command,
        OrderLookup.PayableOrder order
    ) {
        String requested = trimToNull(command.customerId());

        if (requested != null && !requested.equals(order.customerId())) {
            throw new IllegalArgumentException(
                "Customer does not belong to order " + order.orderId()
            );
        }

        return order.customerId();
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private static OutboxEvent paymentCreated(PaymentIntent intent, Instant occurredAt) {
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
        payload.put("status", intent.status().name());
        payload.put("createdAt", intent.createdAt().toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.created",
            PAYMENT_CREATED_VERSION,
            payload,
            occurredAt
        );
    }
}
