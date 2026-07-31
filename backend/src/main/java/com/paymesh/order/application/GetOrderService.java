package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.tenant.MerchantId;

public final class GetOrderService {

    private final OrderRepository orderRepository;

    public GetOrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * The merchantId argument is the authorization, not a filter: an order belonging to another
     * merchant is reported as not found, so an id alone never proves the caller may read the row.
     */
    public Order getById(MerchantId merchantId, OrderId orderId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        if (orderId == null) {
            throw new IllegalArgumentException("Order ID cannot be null");
        }

        return orderRepository
            .findByOrderId(merchantId, orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
