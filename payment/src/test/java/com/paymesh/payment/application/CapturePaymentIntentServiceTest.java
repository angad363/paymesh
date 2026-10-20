package com.paymesh.payment.application;

import com.paymesh.payment.domain.CaptureAmountExceedsAuthorizedException;
import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentNotCapturableException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual capture, in plain JUnit (design spec section 5).
 * <p>
 * The 422/409 mapping and the database's own overcapture refusal are elsewhere --
 * {@code PaymentIntentControllerTest} for the first, {@code PaymentCaptureIntegrationTest} for the
 * second. This class is about what the service decides.
 */
class CapturePaymentIntentServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-02T10:15:30Z");
    private static final String ORDER_ID = "ord_11111111-1111-4111-8111-111111111111";
    private static final long AUTHORIZED = 1999;

    private final InMemoryPaymentIntentRepository repository = new InMemoryPaymentIntentRepository();
    private final Fakes.ImmediateTransactions transactions = new Fakes.ImmediateTransactions();
    private final Fakes.RecordingHistory history = new Fakes.RecordingHistory(transactions);
    private final Fakes.RecordingOutbox outbox = new Fakes.RecordingOutbox(transactions);
    private final Fakes.KnownOrders orders = new Fakes.KnownOrders();
    private final GetPaymentIntentService intents = new GetPaymentIntentService(repository);
    private final CapturePaymentIntentService service = new CapturePaymentIntentService(
        repository, history, intents, orders, outbox, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    // --- full capture ------------------------------------------------------------------

    /** No figure named means all of it, and the intent reaches SUCCEEDED. */
    @Test
    void capturesTheFullAuthorizedAmountWhenNoAmountIsNamed() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        PaymentIntent captured = service.capture(merchantId, intentId, null);

        assertEquals(PaymentIntentStatus.SUCCEEDED, captured.status());
        assertEquals(AUTHORIZED, captured.capturedAmountMinor());
        assertEquals(NOW, captured.updatedAt());
    }

    @Test
    void capturesAnExplicitFullAmount() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        assertEquals(
            AUTHORIZED,
            service.capture(merchantId, intentId, AUTHORIZED).capturedAmountMinor()
        );
    }

    // --- partial capture ---------------------------------------------------------------

    /**
     * PARTIAL CAPTURE STILL REACHES SUCCEEDED, and {@code captured_amount_minor} sits below
     * {@code amount_minor}. That gap is what will make {@code orders.PARTIALLY_PAID} reachable, via
     * the {@code payment.succeeded} consumer that does not exist yet -- never via a second intent,
     * which ADR-011's slot rule forbids.
     * <p>
     * Both CHECK constraints must still hold on this row:
     * {@code ck_payment_intents_succeeded_captured} (captured > 0) and
     * {@code ck_payment_intents_captured} (captured <= amount). The integration test proves it
     * against PostgreSQL; this proves the aggregate produces a row that can satisfy them.
     */
    @Test
    void capturesLessThanAuthorizedAndStillSucceeds() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        PaymentIntent captured = service.capture(merchantId, intentId, 500L);

        assertEquals(PaymentIntentStatus.SUCCEEDED, captured.status());
        assertEquals(500L, captured.capturedAmountMinor());
        assertEquals(AUTHORIZED, captured.amountMinor());
        assertTrue(captured.capturedAmountMinor() < captured.amountMinor());
        assertTrue(captured.capturedAmountMinor() > 0);
    }

    @Test
    void capturesTheSmallestPossibleAmount() {
        MerchantId merchantId = MerchantId.generate();

        assertEquals(
            1L,
            service.capture(merchantId, authorized(merchantId, CaptureMethod.MANUAL), 1L)
                .capturedAmountMinor()
        );
    }

    // --- what is refused ----------------------------------------------------------------

    /**
     * OVERCAPTURE IS REFUSED BEFORE THE DATABASE SEES IT, and the exception names both figures so
     * the API can answer 422 rather than 500.
     * <p>
     * <b>Sabotage that must turn this red:</b> delete the {@code requestedAmountMinor > amountMinor}
     * branch from {@code PaymentIntent.capture}. Here the exception simply stops being thrown; at the
     * HTTP layer the same sabotage turns a 422 into a 500 as
     * {@code ck_payment_intents_captured} takes the refusal instead.
     */
    @Test
    void refusesToCaptureMoreThanWasAuthorized() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        assertThrows(
            CaptureAmountExceedsAuthorizedException.class,
            () -> service.capture(merchantId, intentId, AUTHORIZED + 1)
        );

        assertEquals(
            PaymentIntentStatus.AUTHORIZED,
            repository.findByPaymentIntentId(merchantId, intentId).orElseThrow().status()
        );
        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
    }

    /** Zero is not a capture. A merchant who wants to collect nothing cancels the authorization. */
    @Test
    void refusesToCaptureZero() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        assertThrows(
            IllegalArgumentException.class,
            () -> service.capture(merchantId, intentId, 0L)
        );
    }

    @Test
    void refusesToCaptureANegativeAmount() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        assertThrows(
            IllegalArgumentException.class,
            () -> service.capture(merchantId, intentId, -1L)
        );
    }

    /**
     * Capture is legal from AUTHORIZED and nowhere else. Every other state is either before the
     * provider held anything or already finished with it.
     */
    @Test
    void refusesToCaptureFromAnyStateButAuthorized() {
        MerchantId merchantId = MerchantId.generate();

        for (PaymentIntent intent : java.util.List.of(
            newIntent(merchantId, CaptureMethod.MANUAL),
            newIntent(merchantId, CaptureMethod.MANUAL).attach(PaymentMethodType.CARD, NOW),
            newIntent(merchantId, CaptureMethod.MANUAL)
                .attach(PaymentMethodType.CARD, NOW).confirm(NOW),
            newIntent(merchantId, CaptureMethod.MANUAL)
                .attach(PaymentMethodType.CARD, NOW).confirm(NOW).requireAction(NOW),
            newIntent(merchantId, CaptureMethod.MANUAL)
                .attach(PaymentMethodType.CARD, NOW).confirm(NOW).fail("x", "y", NOW),
            newIntent(merchantId, CaptureMethod.MANUAL).cancel("nope", NOW)
        )) {
            PaymentIntentId intentId = repository.save(intent).paymentIntentId();

            assertThrows(
                PaymentIntentNotCapturableException.class,
                () -> service.capture(merchantId, intentId, null),
                "capture must be refused from " + intent.status()
            );
        }
    }

    /** A second capture finds SUCCEEDED and is refused rather than collecting twice. */
    @Test
    void refusesASecondCapture() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);
        service.capture(merchantId, intentId, null);

        assertThrows(
            PaymentIntentNotCapturableException.class,
            () -> service.capture(merchantId, intentId, null)
        );

        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
    }

    /**
     * AN AUTOMATIC INTENT IS CAPTURED BY THE PROVIDER, NOT BY THE MERCHANT. Allowing this would put
     * two collectors on one authorization: the merchant here and the SUCCEEDED callback on its way.
     */
    @Test
    void refusesToCaptureAnAutomaticIntent() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.AUTOMATIC);

        assertEquals(
            PaymentIntentStatus.AUTHORIZED,
            repository.findByPaymentIntentId(merchantId, intentId).orElseThrow().status()
        );
        assertThrows(
            PaymentIntentNotCapturableException.class,
            () -> service.capture(merchantId, intentId, null)
        );
    }

    /** Another merchant's intent is not found, never forbidden. An id in a path authorizes nothing. */
    @Test
    void refusesToCaptureAnotherMerchantsIntentAndReportsItAsNotFound() {
        MerchantId owner = MerchantId.generate();
        MerchantId outsider = MerchantId.generate();
        PaymentIntentId intentId = authorized(owner, CaptureMethod.MANUAL);

        assertThrows(
            PaymentIntentNotFoundException.class,
            () -> service.capture(outsider, intentId, null)
        );

        assertEquals(
            PaymentIntentStatus.AUTHORIZED,
            repository.findByPaymentIntentId(owner, intentId).orElseThrow().status()
        );
    }

    // --- ADR-013's guard, on the transition that actually takes the money ------------------

    /**
     * AN ORDER THAT IS NO LONGER PAYABLE CANNOT BE CAPTURED AGAINST.
     * <p>
     * ADR-013 put this re-read on confirm because confirm was where money started moving. On a
     * MANUAL intent it is not: confirm only obtains an authorization, and capture is where the funds
     * are taken. Leaving it off here would have closed the hole on one path and reopened it on the
     * other -- PayMesh collecting for an order the merchant cancelled, or one the sweeper expired.
     * <p>
     * The escape is real and is why this refusal is not itself a dead end: AUTHORIZED is cancellable
     * from this PR, so the merchant releases the authorization instead of collecting it.
     */
    @Test
    void refusesToCaptureWhenTheOrderIsNoLongerPayable() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);
        orders.add(merchantId, ORDER_ID, null, AUTHORIZED, "INR", false);

        assertThrows(
            OrderNotPayableException.class,
            () -> service.capture(merchantId, intentId, null)
        );

        assertEquals(
            PaymentIntentStatus.AUTHORIZED,
            repository.findByPaymentIntentId(merchantId, intentId).orElseThrow().status()
        );
        assertEquals(0, outbox.events().size());
    }

    /** An order that has vanished entirely gets the same answer, for the same non-oracle reason. */
    @Test
    void refusesToCaptureWhenTheOrderCannotBeFound() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = repository.save(
            newIntent(merchantId, CaptureMethod.MANUAL)
                .attach(PaymentMethodType.CARD, NOW)
                .confirm(NOW)
                .authorize(NOW)
        ).paymentIntentId();

        assertThrows(
            OrderNotPayableException.class,
            () -> service.capture(merchantId, intentId, null)
        );
    }

    // --- the timeline and the event --------------------------------------------------------

    /**
     * THREE WRITES, ONE TRANSACTION, and the actor is MERCHANT. The provider authorized; the merchant
     * decided to collect, and the timeline must say which.
     */
    @Test
    void writesOneTimelineRowAndOneEventInsideTheTransaction() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        service.capture(merchantId, intentId, null);

        assertEquals(1, transactions.executions());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        PaymentStateChange change = history.changes().get(0);

        assertEquals(PaymentIntentStatus.AUTHORIZED, change.fromStatus());
        assertEquals(PaymentIntentStatus.SUCCEEDED, change.toStatus());
        assertEquals(PaymentStateChange.ActorType.MERCHANT, change.actorType());
        assertEquals(merchantId.value(), change.actorId());
        assertEquals(NOW, change.occurredAt());
    }

    /**
     * The same {@code payment.succeeded} name the provider path emits, and
     * {@code previousStatus: AUTHORIZED} is what distinguishes a merchant capture from an automatic
     * one. A consumer that needed a second event type to tell them apart would be reading the wrong
     * field.
     * <p>
     * <b>AND NOW THE SAME KEYS, WHICH IS A FIX RATHER THAN A RESTATEMENT (ADR-016 section 6).</b>
     * Sharing the event NAME across two emitters was always the design; sharing only some of the
     * PAYLOAD was a bug that no consumer had ever been able to notice, because until the relay
     * existed no event had ever been delivered to anything. This payload carried
     * {@code customerId}, {@code captureMethod} and {@code capturedAt}; the provider path carried
     * {@code occurredAt} and none of the other three -- at the same envelope version 1. Order's
     * consumer reads {@code capturedAmountMinor} and {@code occurredAt}, and it must get both
     * whichever authority collected the money.
     */
    @Test
    void announcesPaymentSucceededCarryingTheCapturedAmount() {
        MerchantId merchantId = MerchantId.generate();
        PaymentIntentId intentId = authorized(merchantId, CaptureMethod.MANUAL);

        service.capture(merchantId, intentId, 500L);

        OutboxEvent event = outbox.events().get(0);

        assertEquals("payment.succeeded", event.eventType());
        assertEquals("PAYMENT_INTENT", event.aggregateType());
        assertEquals(intentId.value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());
        assertEquals(NOW, event.occurredAt());

        Map<String, Object> payload = event.payload();

        assertEquals(intentId.value(), payload.get("paymentIntentId"));
        assertEquals(ORDER_ID, payload.get("orderId"));
        assertEquals(AUTHORIZED, payload.get("amountMinor"));
        // THE FIGURE A CONSUMER MUST NOT ASSUME EQUALS amountMinor. On a partial capture they differ,
        // and an Order consumer reading only amountMinor would mark the order fully PAID.
        assertEquals(500L, payload.get("capturedAmountMinor"));
        assertEquals("MANUAL", payload.get("captureMethod"));
        assertEquals("AUTHORIZED", payload.get("previousStatus"));
        assertEquals("SUCCEEDED", payload.get("status"));
        // occurredAt, and capturedAt is GONE. One key across both emitters, meaning "when the
        // authority that decided this says it happened": the capture instant here, the provider's
        // clock on the callback path. No data was lost -- on this path the two were the same Instant
        // by construction. The absence is asserted as well as the presence, because a payload
        // carrying BOTH would let a consumer read the one the other emitter does not send.
        assertEquals(NOW.toString(), payload.get("occurredAt"));
        assertFalse(payload.containsKey("capturedAt"));
        // Explicit JSON null rather than dropped, so a guest checkout reads the same shape.
        assertTrue(payload.containsKey("customerId"));
        assertNull(payload.get("customerId"));
    }

    // --- helpers ------------------------------------------------------------------------------

    private PaymentIntentId authorized(MerchantId merchantId, CaptureMethod captureMethod) {
        orders.add(merchantId, ORDER_ID, null, AUTHORIZED, "INR", true);

        return repository.save(
            newIntent(merchantId, captureMethod)
                .attach(PaymentMethodType.CARD, NOW)
                .confirm(NOW)
                .authorize(NOW)
        ).paymentIntentId();
    }

    private static PaymentIntent newIntent(MerchantId merchantId, CaptureMethod captureMethod) {
        return PaymentIntent.create(
            PaymentIntentId.generate(),
            merchantId,
            ORDER_ID,
            null,
            AUTHORIZED,
            "INR",
            captureMethod,
            null,
            Map.of(),
            NOW
        );
    }
}
