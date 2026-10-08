package com.paymesh.risk.infrastructure.events;

import com.paymesh.risk.infrastructure.read.PaymentIntentRefStore;
import com.paymesh.shared.outbox.application.EventHandler;
import com.paymesh.shared.outbox.domain.OutboxEvent;

import java.time.Instant;

/**
 * Feeds the {@code payment_intent_ref} read model from Payment's own {@code payment.created}
 * events (ADR-044 section 4) -- the event-fed replacement for the in-process
 * {@code PaymentModuleVelocityLookup} call this deployable cannot make.
 *
 * <h2>THE THREE {@link EventHandler} RULES, AND HOW THIS KEEPS THEM</h2>
 *
 * <ol>
 *   <li><b>No transaction of its own.</b> {@link PaymentIntentRefStore#record} runs inside the
 *       dispatcher's transaction, so the read-model row and the inbox row commit together.</li>
 *   <li><b>Idempotent anyway.</b> The insert is {@code ON CONFLICT (payment_intent_id) DO NOTHING}:
 *       a redelivered event is a safe no-op, not a duplicate row.</li>
 *   <li><b>Throws to retry.</b> A payload missing the fields this projector needs throws, leaving
 *       the event unclaimed so the relay retries -- and, if the payload stays malformed, eventually
 *       dead-letters it (ADR-025), the correct end for an event that cannot be applied.</li>
 * </ol>
 *
 * <h2>GUEST CHECKOUTS CONTRIBUTE NOTHING, ON PURPOSE</h2>
 *
 * {@code EvaluateRiskService.intentsInWindow} already treats a null {@code customerId} as a real
 * zero, not an unknown -- there is no customer to count velocity against. A row with no customer
 * would never be read (every query filters on {@code customer_id}), so skipping it here is not a
 * behavior change, only fewer rows this table stores for nothing.
 *
 * <p>The merchant id comes from the ENVELOPE, not the payload -- the rule every consumer follows:
 * the envelope's merchant was set by the producer from the aggregate; a payload field is data.
 */
public final class PaymentCreatedProjector implements EventHandler {

    /**
     * STABLE, NEVER RENAMED: a primary-key column in {@code processed_events}. Renaming it re-opens
     * this consumer's entire backlog, re-projecting every {@code payment.created} event ever
     * published.
     */
    private static final String CONSUMER_NAME = "payment-intent-ref.payment.created";
    private static final String EVENT_TYPE = "payment.created";

    private final PaymentIntentRefStore store;

    public PaymentCreatedProjector(PaymentIntentRefStore store) {
        this.store = store;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public void handle(OutboxEvent event) {
        Object customerId = event.payload().get("customerId");

        if (customerId == null || customerId.toString().isBlank()) {
            // A guest checkout: nothing to project, and this is a real skip, not a malformed event.
            return;
        }

        Object paymentIntentId = event.payload().get("paymentIntentId");
        Object createdAt = event.payload().get("createdAt");

        if (paymentIntentId == null || createdAt == null) {
            throw new IllegalStateException(
                "payment.created event " + event.eventId().value()
                    + " is missing paymentIntentId or createdAt; cannot project"
            );
        }

        store.record(
            event.merchantId(),
            customerId.toString(),
            paymentIntentId.toString(),
            Instant.parse(createdAt.toString())
        );
    }
}
