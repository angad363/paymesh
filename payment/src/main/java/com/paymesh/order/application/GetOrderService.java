package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Optional;
import java.util.function.BiFunction;

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
        return require(merchantId, orderId, orderRepository::findByOrderId);
    }

    /**
     * The same read, holding a row lock until the caller's transaction ends.
     * <p>
     * It exists for one caller: Payment's create path, which decides whether an order may be
     * collected against and must not have that answer change between reading it and writing the
     * intent. A plain read cannot promise that -- it is a check, not a lock.
     * <p>
     * Order does not know who calls this or why, and it stays that way: the method says "hold this
     * row still", not "a payment is being created". <b>Order must never learn that Payment exists</b>
     * (design section 0.5), so the coupling runs the other way -- Payment reaches this through its
     * own port and Order gains nothing.
     * <p>
     * MUST be called inside a transaction. Outside one the lock has nothing to be held for.
     */
    public Order getByIdForUpdate(MerchantId merchantId, OrderId orderId) {
        return require(merchantId, orderId, orderRepository::findByOrderIdForUpdate);
    }

    private Order require(
        MerchantId merchantId,
        OrderId orderId,
        BiFunction<MerchantId, OrderId, Optional<Order>> read
    ) {
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant ID cannot be null");
        }

        if (orderId == null) {
            throw new IllegalArgumentException("Order ID cannot be null");
        }

        return read.apply(merchantId, orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }
}
