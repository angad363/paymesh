package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
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

public final class AttachPaymentMethodService {

    /** SDD 22.1. Bump only when the payload below stops being readable by an existing consumer. */
    private static final int PAYMENT_METHOD_ATTACHED_VERSION = 1;

    private final PaymentIntentRepository paymentIntents;
    private final PaymentStateHistoryRepository history;
    private final GetPaymentIntentService getPaymentIntentService;
    private final OutboxWriter outbox;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public AttachPaymentMethodService(
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
     * Records WHICH KIND of instrument will be used, moving the intent to REQUIRES_CONFIRMATION.
     * <p>
     * The service does not decide whether the transition is allowed -- it loads the aggregate and
     * asks, so the state machine has exactly one implementation.
     * <p>
     * No order re-read here, deliberately. Attaching moves no money and commits PayMesh to nothing;
     * confirm is the transition that has to know the order is still payable (ADR-013), and putting
     * the same guard on both would buy a second answer that the first already covers.
     */
    public PaymentIntent attach(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        PaymentMethodType paymentMethodType
    ) {
        Instant now = Instant.now(clock);

        // ONE TRANSACTION, THREE WRITES (ADR-010), and the read is inside it too: the state the
        // aggregate refuses on has to be the state that is still true when the update lands.
        return transactions.execute(status -> {
            // Locked, for the same reason confirm locks: two concurrent attaches would otherwise
            // race on the intent's optimistic version and hand the loser a 500 instead of the 409
            // that says a method is already attached.
            PaymentIntent intent = getPaymentIntentService.getByIdForUpdate(
                merchantId, paymentIntentId
            );
            PaymentIntentStatus from = intent.status();
            PaymentIntent saved = paymentIntents.save(intent.attach(paymentMethodType, now));

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

            outbox.append(paymentMethodAttached(saved, from, now));

            return saved;
        });
    }

    /**
     * NOT AN EVENT THE SDD NAMES. Its catalog has {@code payment.created},
     * {@code payment.processing}, {@code payment.succeeded} and {@code payment.failed}, and nothing
     * for attach.
     * <p>
     * It is emitted anyway, under the rule the previous PR settled when it added
     * {@code payment.cancelled}: a transition that changes what a consumer would believe gets an
     * event, in the transition's own transaction. A consumer fed only creation and processing sees
     * an intent jump from "awaiting a method" to "collecting" with no record of which instrument was
     * chosen -- and the method type is exactly what a reporting or risk read model wants to slice
     * by. A stream with a hole in it is worse than no stream, because it looks complete.
     */
    private static OutboxEvent paymentMethodAttached(
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
        // The KIND of instrument, never the instrument. There is no token, no PAN and no holder
        // name in this payload, and an event is the last place raw instrument data should reach.
        payload.put("paymentMethodType", intent.paymentMethodType().name());
        payload.put("previousStatus", from.name());
        payload.put("status", intent.status().name());
        payload.put("attachedAt", occurredAt.toString());

        return new OutboxEvent(
            EventId.generate(),
            intent.merchantId(),
            "PAYMENT_INTENT",
            intent.paymentIntentId().value(),
            "payment.method_attached",
            PAYMENT_METHOD_ATTACHED_VERSION,
            payload,
            occurredAt
        );
    }
}
