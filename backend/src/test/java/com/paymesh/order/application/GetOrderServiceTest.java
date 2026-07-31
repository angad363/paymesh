package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.tenant.MerchantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GetOrderServiceTest {

    private final OrderRepository repository = new InMemoryOrderRepository();
    private final GetOrderService service = new GetOrderService(repository);

    @Test
    void readsBackAnOrderOfTheCallersMerchant() {
        MerchantId merchantId = MerchantId.generate();
        Order order = repository.save(order(merchantId));

        assertEquals(order.orderId(), service.getById(merchantId, order.orderId()).orderId());
    }

    @Test
    void reportsAnUnknownOrderAsNotFound() {
        assertThrows(
            OrderNotFoundException.class,
            () -> service.getById(MerchantId.generate(), OrderId.generate())
        );
    }

    /**
     * The isolation invariant. The id is real and the caller is authenticated; the order simply
     * belongs to someone else, so it does not exist as far as this caller is concerned. Reporting
     * it as forbidden instead would confirm the id and turn the endpoint into a tenant oracle.
     */
    @Test
    void hidesAnOrderBelongingToAnotherMerchant() {
        Order order = repository.save(order(MerchantId.generate()));

        assertThrows(
            OrderNotFoundException.class,
            () -> service.getById(MerchantId.generate(), order.orderId())
        );
    }

    @Test
    void rejectsAMissingMerchant() {
        assertThrows(IllegalArgumentException.class, () -> service.getById(null, OrderId.generate()));
    }

    private static Order order(MerchantId merchantId) {
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
            Instant.parse("2026-07-31T10:15:30Z")
        );
    }
}
