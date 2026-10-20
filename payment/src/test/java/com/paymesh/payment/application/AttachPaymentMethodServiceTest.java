package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.payment.domain.PaymentStateChange;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttachPaymentMethodServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";

    private final InMemoryPaymentIntentRepository repository = new InMemoryPaymentIntentRepository();
    private final Fakes.ImmediateTransactions transactions = new Fakes.ImmediateTransactions();
    private final Fakes.RecordingHistory history = new Fakes.RecordingHistory(transactions);
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox(transactions);
    private final GetPaymentIntentService intents = new GetPaymentIntentService(repository);
    private final AttachPaymentMethodService service = new AttachPaymentMethodService(
        repository, history, intents, outbox, transactions, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void attachesAMethodAndAwaitsConfirmation() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingAMethod(merchantId);

        PaymentIntent attached = service.attach(merchantId, intentId, PaymentMethodType.UPI);

        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, attached.status());
        assertEquals(PaymentMethodType.UPI, attached.paymentMethodType());
        assertEquals(NOW, attached.updatedAt());
    }

    /**
     * ONE TRANSITION, ONE HISTORY ROW, ONE EVENT, ALL INSIDE ONE TRANSACTION. The counts matter as
     * much as the values: two rows for one attach corrupts a timeline whose only purpose is to be
     * exact, and the recorded flags are what a service writing outside the wrap would fail.
     */
    @Test
    void writesTheTransitionItsTimelineRowAndItsEventInOneTransaction() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingAMethod(merchantId);

        service.attach(merchantId, intentId, PaymentMethodType.CARD);

        assertEquals(1, transactions.executions());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        PaymentStateChange change = history.changes().get(0);

        assertEquals(PaymentIntentStatus.REQUIRES_PAYMENT_METHOD, change.fromStatus());
        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, change.toStatus());
        assertEquals(PaymentStateChange.ActorType.MERCHANT, change.actorType());
        assertEquals(merchantId.value(), change.actorId());
        assertEquals(NOW, change.occurredAt());
    }

    /**
     * The event carries the method TYPE and nothing resembling an instrument. An event is the last
     * place raw instrument data should reach, because it is the copy that leaves the system.
     */
    @Test
    void announcesTheAttachedMethodType() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingAMethod(merchantId);

        service.attach(merchantId, intentId, PaymentMethodType.WALLET);

        OutboxEvent event = outbox.events().get(0);
        Map<String, Object> payload = event.payload();

        assertEquals("payment.method_attached", event.eventType());
        assertEquals("PAYMENT_INTENT", event.aggregateType());
        assertEquals(intentId.value(), event.aggregateId());
        assertEquals("WALLET", payload.get("paymentMethodType"));
        assertEquals("REQUIRES_PAYMENT_METHOD", payload.get("previousStatus"));
        assertEquals("REQUIRES_CONFIRMATION", payload.get("status"));
    }

    @Test
    void refusesToAttachTwiceAndAnnouncesNothingWhenItDoes() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingAMethod(merchantId);
        service.attach(merchantId, intentId, PaymentMethodType.CARD);

        assertThrows(
            com.paymesh.payment.domain.PaymentMethodNotAttachableException.class,
            () -> service.attach(merchantId, intentId, PaymentMethodType.UPI)
        );

        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
    }

    /**
     * The intent is real; it just belongs to someone else. Reported as not found, so a {@code pi_}
     * in a path never proves the caller may write to the row.
     */
    @Test
    void refusesToAttachToAnotherMerchantsIntent() {
        MerchantId owner = MerchantId.generate();
        PaymentIntentId intentId = awaitingAMethod(owner);

        assertThrows(
            PaymentIntentNotFoundException.class,
            () -> service.attach(MerchantId.generate(), intentId, PaymentMethodType.CARD)
        );

        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
    }

    private PaymentIntentId awaitingAMethod(MerchantId merchantId) {
        return repository.save(PaymentIntent.create(
            PaymentIntentId.generate(),
            merchantId,
            ORDER_ID,
            null,
            1999,
            "INR",
            null,
            null,
            Map.of(),
            NOW
        )).paymentIntentId();
    }
}
