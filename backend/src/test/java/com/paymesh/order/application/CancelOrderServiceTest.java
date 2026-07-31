package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderNotCancellableException;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CancelOrderServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T10:15:30Z");

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService getOrderService = new GetOrderService(repository);
    private final CancelOrderService service =
        new CancelOrderService(repository, getOrderService, Clock.fixed(NOW, ZoneOffset.UTC));

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
