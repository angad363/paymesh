package com.paymesh.order.application;

import com.paymesh.order.application.Fakes.ImmediateTransactions;
import com.paymesh.order.application.Fakes.RecordingHistory;
import com.paymesh.order.application.Fakes.RecordingOutbox;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a successful payment does to an order, in plain JUnit with no event, no relay and no
 * database (ADR-016).
 * <p>
 * NOTE WHAT IS NOT IMPORTED: anything from {@code com.paymesh.payment}. This test drives the service
 * with a merchant, an order id, a {@code long} and an {@code Instant}, which is the whole contract --
 * {@code ModuleBoundaryTest.orderNeverImportsPayment} keeps an EMPTY allowlist, and a rule that
 * needed a payment intent to test would be a rule that had already broken it.
 */
class ApplyPaymentSucceededServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-08-02T10:00:00Z");
    private static final Instant PAID_AT = Instant.parse("2026-08-02T12:00:00Z");

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService getOrderService = new GetOrderService(repository);
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final RecordingOutbox outbox = new RecordingOutbox(transactions);
    private final RecordingHistory history = new RecordingHistory(transactions);

    private final ApplyPaymentSucceededService service =
        new ApplyPaymentSucceededService(repository, history, getOrderService, outbox);

    // --- the amount rule, which is the one worth breaking things over -------------------------

    /**
     * A COLLECTION EQUAL TO THE ORDER'S OWN AMOUNT SETTLES IT. {@code amount_paid_minor} moves too --
     * a PAID order whose paid column still reads zero is a row no reconciliation can explain.
     */
    @Test
    void marksTheOrderPaidWhenTheWholeAmountWasCollected() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 1999));

        assertTrue(service.apply(merchantId, order.orderId(), 1999, PAID_AT));

        Order applied = repository.findByOrderId(merchantId, order.orderId()).orElseThrow();

        assertEquals(OrderStatus.PAID, applied.status());
        assertEquals(1999, applied.amountPaidMinor());
        assertEquals(PAID_AT, applied.updatedAt());
    }

    /**
     * A PARTIAL CAPTURE YIELDS PARTIALLY_PAID, AND THE COMPARISON IS AGAINST THE ORDER.
     * <p>
     * <b>Sabotage that must turn this red:</b> compare the captured figure against the payload's own
     * {@code amountMinor} instead of the order's. On the capture path those two are equal, so the
     * order comes out PAID and the merchant's outstanding balance quietly disappears.
     */
    @Test
    void marksTheOrderPartiallyPaidWhenLessThanTheWholeAmountWasCollected() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 4000));

        assertTrue(service.apply(merchantId, order.orderId(), 3000, PAID_AT));

        Order applied = repository.findByOrderId(merchantId, order.orderId()).orElseThrow();

        assertEquals(OrderStatus.PARTIALLY_PAID, applied.status());
        assertEquals(3000, applied.amountPaidMinor());
    }

    // --- idempotency -----------------------------------------------------------------------------

    /**
     * APPLYING TWICE WRITES NOTHING TWICE, and this guard is not the inbox's. The inbox stops the
     * SAME event arriving twice; this stops a DIFFERENT event describing the same collection -- a
     * reconciliation re-announcing an outcome under a fresh id -- from double-applying. Neither
     * subsumes the other.
     * <p>
     * The ROW COUNTS are what this asserts, not the status: the status is right either way, and a
     * service that appended a second timeline row and a second event for one collection would still
     * leave a PAID order behind. Announcing it twice is the bug.
     */
    @Test
    void writesNothingASecondTimeWhenTheSamePaymentIsAppliedAgain() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 1999));

        assertTrue(service.apply(merchantId, order.orderId(), 1999, PAID_AT));
        assertFalse(service.apply(merchantId, order.orderId(), 1999, PAID_AT.plusSeconds(5)));

        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertEquals(
            PAID_AT,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().updatedAt()
        );
    }

    /**
     * A CANCELLED ORDER IS NOT RESURRECTED. The merchant said they did not want it, and a late or
     * replayed payment event must not overturn that on its own -- the money is the payment
     * capability's problem to release, not a reason to revive the obligation.
     */
    @Test
    void leavesACancelledOrderAlone() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(
            pendingOrder(merchantId, 1999).cancel("changed my mind", CREATED_AT.plusSeconds(60))
        );

        assertFalse(service.apply(merchantId, order.orderId(), 1999, PAID_AT));

        assertEquals(
            OrderStatus.CANCELLED,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
        assertEquals(0, history.changes().size());
        assertEquals(0, outbox.events().size());
    }

    // --- the timeline and the event ---------------------------------------------------------------

    /**
     * SYSTEM, WITH NO ACTOR. There is no principal behind a consumer, and naming the merchant would
     * claim the merchant asked for this. V11 predicted this exact row two migrations early, and
     * {@code ck_order_state_history_actor} admits nothing else -- in particular not PROVIDER, which
     * would make the boundary violation spellable.
     */
    @Test
    void writesOneSystemTimelineRowAndOneEvent() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 1999));

        service.apply(merchantId, order.orderId(), 1999, PAID_AT);

        assertEquals(1, history.changes().size());

        OrderStateChange change = history.changes().get(0);

        assertEquals(order.orderId(), change.orderId());
        assertEquals(merchantId, change.merchantId());
        assertEquals(OrderStatus.PENDING, change.fromStatus());
        assertEquals(OrderStatus.PAID, change.toStatus());
        assertEquals(OrderStateChange.ActorType.SYSTEM, change.actorType());
        assertNull(change.actorId());
        assertEquals(PAID_AT, change.occurredAt());
    }

    /**
     * TWO EVENT NAMES, NOT ONE WITH A STATUS FIELD. A consumer subscribing to "this order is settled"
     * must not have to open a payload to discover that it is not.
     */
    @Test
    void announcesOrderPaidOrOrderPartiallyPaidByName() {
        MerchantId merchantId = MerchantId.generate();
        Order full = repository.save(pendingOrder(merchantId, 1999));
        Order partial = repository.save(pendingOrder(merchantId, 4000));

        service.apply(merchantId, full.orderId(), 1999, PAID_AT);
        service.apply(merchantId, partial.orderId(), 3000, PAID_AT);

        assertEquals("order.paid", outbox.events().get(0).eventType());
        assertEquals("order.partially_paid", outbox.events().get(1).eventType());
    }

    @Test
    void carriesTheOrderAndTheAmountsInTheEventPayload() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 4000));

        service.apply(merchantId, order.orderId(), 3000, PAID_AT);

        OutboxEvent event = outbox.events().get(0);

        assertEquals("ORDER", event.aggregateType());
        assertEquals(order.orderId().value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());
        assertEquals(PAID_AT, event.occurredAt());

        Map<String, Object> payload = event.payload();

        assertEquals(order.orderId().value(), payload.get("orderId"));
        assertEquals(merchantId.value(), payload.get("merchantId"));
        assertEquals(4000L, payload.get("amountMinor"));
        assertEquals(3000L, payload.get("amountPaidMinor"));
        assertEquals("INR", payload.get("currency"));
        assertEquals("PENDING", payload.get("previousStatus"));
        assertEquals("PARTIALLY_PAID", payload.get("status"));
        assertEquals(PAID_AT.toString(), payload.get("occurredAt"));
    }

    /**
     * IT OPENS NO TRANSACTION OF ITS OWN, and that is the one place this service deliberately breaks
     * the pattern every other write service follows (ADR-010). It runs inside the dispatcher's, which
     * already holds the {@code processed_events} row claiming the event; a second transaction here
     * would let the state change commit independently of that row, and a crash between the two would
     * double-apply the payment on redelivery.
     */
    @Test
    void opensNoTransactionOfItsOwn() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId, 1999));

        service.apply(merchantId, order.orderId(), 1999, PAID_AT);

        assertEquals(0, transactions.executions());
    }

    // --- tenancy ------------------------------------------------------------------------------------

    /**
     * The merchant argument is the authorization, not a filter. An event naming another merchant's
     * order finds nothing, exactly as a merchant-facing read would, and throws rather than silently
     * doing nothing -- the relay retries it, and a genuine cross-tenant event is a corruption worth
     * a WARN on every pass.
     */
    @Test
    void refusesToApplyAPaymentToAnotherMerchantsOrder() {
        MerchantId owner = MerchantId.generate();
        MerchantId stranger = MerchantId.generate();
        Order order = repository.save(pendingOrder(owner, 1999));

        org.junit.jupiter.api.Assertions.assertThrows(
            OrderNotFoundException.class,
            () -> service.apply(stranger, order.orderId(), 1999, PAID_AT)
        );

        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(owner, order.orderId()).orElseThrow().status()
        );
    }

    /** An event naming an order this platform does not have is a failure, not a silent skip. */
    @Test
    void refusesToApplyAPaymentToAnOrderThatDoesNotExist() {
        org.junit.jupiter.api.Assertions.assertThrows(
            OrderNotFoundException.class,
            () -> service.apply(MerchantId.generate(), OrderId.generate(), 1999, PAID_AT)
        );
    }

    // --- helpers --------------------------------------------------------------------------------------

    private static Order pendingOrder(MerchantId merchantId, long amountMinor) {
        return Order.create(
            OrderId.generate(),
            merchantId,
            null,
            null,
            amountMinor,
            "INR",
            null,
            Map.of(),
            null,
            CREATED_AT
        );
    }
}
