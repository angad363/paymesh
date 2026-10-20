package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
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

    /**
     * The same read. A list cannot hold a row still, so what this double preserves is the SIGNATURE
     * and the tenant scoping, not the lock -- the locking behaviour is proved against PostgreSQL,
     * because it is the only thing that can arbitrate it.
     */
    @Override
    public Optional<Order> findByOrderIdForUpdate(MerchantId merchantId, OrderId orderId) {
        return findByOrderId(merchantId, orderId);
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

    /**
     * NO TENANT PREDICATE, matching the real query. That is not an omission in the double: the
     * expiry sweeper runs on a timer with no tenant and derives one from each row it finds, so a
     * double that filtered by merchant here would be testing a rule the production code does not
     * have -- and would hide a sweeper that only ever saw one merchant's orders.
     * <p>
     * The predicate and the ordering match the JPQL exactly (PENDING, a non-null expiry at or before
     * {@code now}, oldest deadline first), so a service test that relies on the batch order or on an
     * ineligible row being excluded fails here too rather than only against PostgreSQL.
     */
    @Override
    public List<ExpirableOrder> findExpirable(Instant now, int limit) {
        return orders.stream()
            .filter(order -> order.status() == OrderStatus.PENDING)
            .filter(order -> order.expiresAt() != null && !order.expiresAt().isAfter(now))
            .sorted(Comparator.comparing(Order::expiresAt))
            .limit(limit)
            .map(order -> new ExpirableOrder(
                order.merchantId().value(), order.orderId().value()
            ))
            .toList();
    }
}
