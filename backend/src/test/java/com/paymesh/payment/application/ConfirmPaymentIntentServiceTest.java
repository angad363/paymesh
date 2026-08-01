package com.paymesh.payment.application;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentAttemptStatus;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentNotConfirmableException;
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

class ConfirmPaymentIntentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";

    private final InMemoryPaymentIntentRepository repository = new InMemoryPaymentIntentRepository();
    private final Fakes.ImmediateTransactions transactions = new Fakes.ImmediateTransactions();
    private final Fakes.RecordingAttempts attempts = new Fakes.RecordingAttempts(transactions);
    private final Fakes.RecordingHistory history = new Fakes.RecordingHistory(transactions);
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox(transactions);
    private final Fakes.KnownOrders orders = new Fakes.KnownOrders();
    private final GetPaymentIntentService intents = new GetPaymentIntentService(repository);
    private final ConfirmPaymentIntentService service = new ConfirmPaymentIntentService(
        repository,
        attempts,
        history,
        intents,
        orders,
        outbox,
        transactions,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void confirmsAnAttachedIntentIntoProcessing() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        PaymentIntent processing = service.confirm(command(merchantId, intentId, null, null));

        assertEquals(PaymentIntentStatus.PROCESSING, processing.status());
        assertEquals(PaymentMethodType.CARD, processing.paymentMethodType());
        assertEquals(NOW, processing.updatedAt());
    }

    /** Exactly one attempt, numbered 1, at PROCESSING, against the simulator that does not exist. */
    @Test
    void opensExactlyOneAttemptNumberedOne() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        service.confirm(command(merchantId, intentId, null, null));

        assertEquals(1, attempts.attempts().size());

        PaymentAttempt attempt = attempts.attempts().get(0);

        assertEquals(1, attempt.attemptNumber());
        assertEquals(PaymentAttemptStatus.PROCESSING, attempt.status());
        assertEquals(PaymentAttempt.SIMULATOR, attempt.provider());
        assertEquals(intentId, attempt.paymentIntentId());
        assertEquals(1999L, attempt.amountMinor());
        assertEquals("INR", attempt.currency());
    }

    /**
     * FOUR WRITES, ONE TRANSACTION. The attempt, the intent, its timeline row and its event are one
     * fact; a confirm that committed the attempt and then failed would leave a collection under way
     * with an intent that never learned about it.
     */
    @Test
    void writesTheAttemptTheTransitionItsTimelineAndItsEventInOneTransaction() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        service.confirm(command(merchantId, intentId, null, null));

        assertEquals(1, transactions.executions());
        assertTrue(attempts.appendedInsideATransaction());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        assertEquals(1, history.changes().size());

        PaymentStateChange change = history.changes().get(0);

        assertEquals(PaymentIntentStatus.REQUIRES_CONFIRMATION, change.fromStatus());
        assertEquals(PaymentIntentStatus.PROCESSING, change.toStatus());
        assertEquals(PaymentStateChange.ActorType.MERCHANT, change.actorType());
    }

    @Test
    void announcesThatCollectionHasStarted() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        service.confirm(command(merchantId, intentId, null, null));

        assertEquals(1, outbox.events().size());

        OutboxEvent event = outbox.events().get(0);

        assertEquals("payment.processing", event.eventType());
        assertEquals(intentId.value(), event.aggregateId());
        assertEquals("REQUIRES_CONFIRMATION", event.payload().get("previousStatus"));
        assertEquals("PROCESSING", event.payload().get("status"));
        assertEquals("CARD", event.payload().get("paymentMethodType"));
    }

    // --- the guard (ADR-013) -------------------------------------------------------

    /**
     * THE TEST THIS WHOLE GUARD EXISTS FOR. The intent was created while the order was payable and
     * the order has since been cancelled. Payment must refuse to collect for an order the merchant
     * explicitly cancelled -- and it must refuse having written nothing, or a refused confirm would
     * still leave an attempt in flight.
     */
    @Test
    void refusesToConfirmAgainstAnOrderThatIsNoLongerPayable() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        // The order is cancelled after the intent exists, exactly as Order.cancel would leave it.
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", false);

        assertThrows(
            OrderNotPayableException.class,
            () -> service.confirm(command(merchantId, intentId, null, null))
        );

        assertEquals(0, attempts.attempts().size());
        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
        assertEquals(
            PaymentIntentStatus.REQUIRES_CONFIRMATION,
            intents.getById(merchantId, intentId).status()
        );
    }

    /** An order that has vanished entirely gets the same answer, for the same anti-oracle reason. */
    @Test
    void refusesToConfirmAgainstAnOrderThatCannotBeFound() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, false);

        assertThrows(
            OrderNotPayableException.class,
            () -> service.confirm(command(merchantId, intentId, null, null))
        );

        assertEquals(0, attempts.attempts().size());
    }

    // --- refusals ------------------------------------------------------------------

    @Test
    void refusesToConfirmAnIntentWithNoMethodAttached() {
        MerchantId merchantId = MerchantId.generate();
        orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);

        PaymentIntentId intentId = repository.save(newIntent(merchantId)).paymentIntentId();

        assertThrows(
            PaymentIntentNotConfirmableException.class,
            () -> service.confirm(command(merchantId, intentId, null, null))
        );

        assertEquals(0, attempts.attempts().size());
        assertEquals(0, outbox.events().size());
    }

    @Test
    void refusesToConfirmAnotherMerchantsIntent() {
        MerchantId owner = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(owner, true);

        assertThrows(
            PaymentIntentNotFoundException.class,
            () -> service.confirm(command(MerchantId.generate(), intentId, null, null))
        );

        assertEquals(0, attempts.attempts().size());
    }

    @Test
    void rejectsNullCommand() {
        assertThrows(IllegalArgumentException.class, () -> service.confirm(null));
    }

    // --- what confirm carries ---------------------------------------------------------

    /** Accepted, redacted, stored, and read by nothing (design section 3.6). */
    @Test
    void storesTheReturnUrlAndDeviceOnTheAttemptAfterRedaction() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = awaitingConfirmation(merchantId, true);

        service.confirm(command(
            merchantId, intentId, "https://shop.test/return?session=SECRET", "ios"
        ));

        assertEquals(
            Map.of("returnUrl", "https://shop.test/return", "device", "ios"),
            attempts.attempts().get(0).requestPayload()
        );
    }

    private static ConfirmPaymentIntentCommand command(
        MerchantId merchantId,
        PaymentIntentId paymentIntentId,
        String returnUrl,
        String device
    ) {
        return new ConfirmPaymentIntentCommand(merchantId, paymentIntentId, returnUrl, device);
    }

    private PaymentIntentId awaitingConfirmation(MerchantId merchantId, boolean orderIsPayable) {
        if (orderIsPayable) {
            orders.add(merchantId, ORDER_ID, null, 1999, "INR", true);
        }

        return repository.save(newIntent(merchantId).attach(PaymentMethodType.CARD, NOW))
            .paymentIntentId();
    }

    private static PaymentIntent newIntent(MerchantId merchantId) {
        return PaymentIntent.create(
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
        );
    }
}
