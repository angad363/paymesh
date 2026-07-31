package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Test double for the persistence port. It enforces the same tenant scoping the real adapter gets
 * from the "where merchant_id = ?" predicate, and the same keyset ordering the real query gets from
 * "order by created_at desc, order_id desc", so a service test that leaks across tenants or pages
 * ambiguously fails here too rather than only in the container-backed test.
 */
final class InMemoryOrderRepository implements OrderRepository {

    private final List<Order> orders = new ArrayList<>();

    @Override
    public boolean existsByMerchantOrderReference(MerchantId merchantId, String merchantOrderReference) {
        return orders.stream()
            .anyMatch(order -> order.merchantId().equals(merchantId)
                && merchantOrderReference.equals(order.merchantOrderReference()));
    }

    @Override
    public Order save(Order order) {
        orders.removeIf(stored -> stored.orderId().equals(order.orderId()));
        orders.add(order);
        return order;
    }

    @Override
    public Optional<Order> findByOrderId(MerchantId merchantId, OrderId orderId) {
        return orders.stream()
            .filter(order -> order.merchantId().equals(merchantId) && order.orderId().equals(orderId))
            .findFirst();
    }

    @Override
    public List<Order> findPage(MerchantId merchantId, OrderStatus status, OrderCursor cursor, int limit) {
        return orders.stream()
            .filter(order -> order.merchantId().equals(merchantId))
            .filter(order -> status == null || order.status() == status)
            .filter(order -> cursor.isAfter(order.createdAt(), order.orderId().value()))
            .sorted(Comparator
                .comparing(Order::createdAt)
                .thenComparing((Order order) -> order.orderId().value())
                .reversed())
            .limit(limit)
            .toList();
    }
}
