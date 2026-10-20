package com.paymesh.order.application;

import com.paymesh.order.application.Fakes.ImmediateTransactions;
import com.paymesh.order.application.Fakes.RecordingHistory;
import com.paymesh.order.application.Fakes.RecordingOutbox;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderNotCancellableException;
import com.paymesh.order.domain.OrderStateChange;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.outbox.domain.OutboxEvent;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService getOrderService = new GetOrderService(repository);
    private final ImmediateTransactions transactions = new ImmediateTransactions();
    private final RecordingOutbox outbox = new RecordingOutbox(transactions);
    private final RecordingHistory history = new RecordingHistory(transactions);
    private final CancelOrderService service = new CancelOrderService(
        repository, history, getOrderService, outbox, transactions,
        Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void cancelsAPendingOrderAndStampsItWithTheInjectedClock() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));

        Order cancelled = service.cancel(merchantId, order.orderId(), "  out of stock  ");

        assertEquals(OrderStatus.CANCELLED, cancelled.status());
        assertEquals("out of stock", cancelled.cancellationReason());
        assertEquals(NOW, cancelled.cancelledAt());
    }

    @Test
    void persistsTheCancellation() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));

        service.cancel(merchantId, order.orderId(), null);

        assertEquals(
            OrderStatus.CANCELLED,
            repository.findByOrderId(merchantId, order.orderId()).orElseThrow().status()
        );
    }

    /** The second cancel has nothing left to do and must say so rather than rewrite the first. */
    @Test
    void refusesASecondCancellation() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));
        service.cancel(merchantId, order.orderId(), "first");

        assertThrows(
            OrderNotCancellableException.class,
            () -> service.cancel(merchantId, order.orderId(), "second")
        );
    }

    @Test
    void reportsAnUnknownOrderAsNotFound() {
        assertThrows(
            OrderNotFoundException.class,
            () -> service.cancel(MerchantId.generate(), OrderId.generate(), null)
        );
    }

    /**
     * Cancelling is a write, so the tenant check matters even more than on a read: without it, an
     * id leaked from a response would let one merchant cancel another's order. Not found, not
     * forbidden -- the answer must not confirm the order exists.
     */
    @Test
    void refusesToCancelAnotherMerchantsOrderAndReportsItAsNotFound() {
        MerchantId owner = MerchantId.generate();
        MerchantId outsider = MerchantId.generate();
        Order order = repository.save(pendingOrder(owner));

        assertThrows(
            OrderNotFoundException.class,
            () -> service.cancel(outsider, order.orderId(), null)
        );

        assertEquals(
            OrderStatus.PENDING,
            repository.findByOrderId(owner, order.orderId()).orElseThrow().status()
        );
    }

    // --- the timeline and the event ------------------------------------------------

    /**
     * ONE TIMELINE ROW AND ONE EVENT, BOTH INSIDE THE TRANSACTION THAT MOVED THE ORDER.
     * <p>
     * The counts are as load-bearing as the values. This service used to be two unwrapped statements
     * with no transaction, no history and no event at all, so the recorded FLAGS -- not merely the
     * presence of the rows -- are what would catch a refactor that moved either write back outside
     * the wrap.
     */
    @Test
    void writesOneTimelineRowAndOneEventInsideTheTransaction() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));

        service.cancel(merchantId, order.orderId(), "out of stock");

        assertEquals(1, transactions.executions());
        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
        assertTrue(history.appendedInsideATransaction());
        assertTrue(outbox.appendedInsideATransaction());

        OrderStateChange change = history.changes().get(0);

        assertEquals(OrderStatus.PENDING, change.fromStatus());
        assertEquals(OrderStatus.CANCELLED, change.toStatus());
        assertEquals(OrderStateChange.ActorType.MERCHANT, change.actorType());
        assertEquals(merchantId.value(), change.actorId());
        assertEquals("out of stock", change.reason());
        assertEquals(NOW, change.occurredAt());
    }

    @Test
    void carriesTheCancellationInTheEventPayload() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));

        service.cancel(merchantId, order.orderId(), "out of stock");

        OutboxEvent event = outbox.events().get(0);

        assertEquals("order.cancelled", event.eventType());
        assertEquals("ORDER", event.aggregateType());
        assertEquals(order.orderId().value(), event.aggregateId());
        assertEquals(merchantId, event.merchantId());
        assertEquals(1, event.eventVersion());

        Map<String, Object> payload = event.payload();

        assertEquals(order.orderId().value(), payload.get("orderId"));
        assertEquals(1999L, payload.get("amountMinor"));
        // The state it came from. A consumer reconciling a timeline cannot order two events without
        // it, and PENDING will not always be the only status this is reachable from.
        assertEquals("PENDING", payload.get("previousStatus"));
        assertEquals("CANCELLED", payload.get("status"));
        assertEquals("out of stock", payload.get("cancellationReason"));
        assertEquals(NOW.toString(), payload.get("cancelledAt"));
        // Carried as an explicit JSON null rather than dropped, so a consumer reads the same shape
        // for a guest checkout as for a linked one.
        assertTrue(payload.containsKey("customerId"));
        assertNull(payload.get("customerId"));
    }

    /** A refused cancel announces nothing and records no move that never happened. */
    @Test
    void announcesNothingWhenTheCancellationIsRefused() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(pendingOrder(merchantId));
        service.cancel(merchantId, order.orderId(), "first");

        assertThrows(
            OrderNotCancellableException.class,
            () -> service.cancel(merchantId, order.orderId(), "second")
        );

        assertEquals(1, history.changes().size());
        assertEquals(1, outbox.events().size());
    }

    private static Order pendingOrder(MerchantId merchantId) {
        return Order.create(
            OrderId.generate(),
            merchantId,
            null,
            null,
            1999,
            "INR",
            null,
            Map.of(),
            null,
            NOW.minusSeconds(60)
        );
    }
}
