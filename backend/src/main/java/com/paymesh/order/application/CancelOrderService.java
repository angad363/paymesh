package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Clock;
import java.time.Instant;

public final class CancelOrderService {

    private final OrderRepository orderRepository;
    private final GetOrderService getOrderService;
    private final Clock clock;

    public CancelOrderService(
        OrderRepository orderRepository,
        GetOrderService getOrderService,
        Clock clock
    ) {
        this.orderRepository = orderRepository;
        this.getOrderService = getOrderService;
        this.clock = clock;
    }

    /**
     * Requests cancellation. The service does not decide whether it is allowed -- it loads the
     * aggregate and asks, so the state machine has exactly one implementation and a second caller
     * cannot reach a different conclusion.
     */
    public Order cancel(MerchantId merchantId, OrderId orderId, String reason) {
        Order order = getOrderService.getById(merchantId, orderId);

        return orderRepository.save(order.cancel(reason, Instant.now(clock)));
    }
}
