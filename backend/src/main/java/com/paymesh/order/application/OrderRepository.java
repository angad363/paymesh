package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.List;
import java.util.Optional;

/**
 * The order capability's persistence port.
 * <p>
 * Every read takes a MerchantId. That is the tenant boundary expressed as a method signature: there
 * is deliberately no findByOrderId(OrderId), because a caller holding only an id -- guessed, leaked,
 * or copied from another merchant's response -- must not be able to reach a row. Adding such an
 * overload would silently remove tenant isolation from every caller at once.
 */
public interface OrderRepository {

    /**
     * A convenience for a friendlier error, NOT the uniqueness guarantee. Two concurrent creates can
     * both pass this and the constraint is what stops both from landing, so an implementation must
     * still translate the violation rather than rely on callers checking first.
     */
    boolean existsByMerchantOrderReference(MerchantId merchantId, String merchantOrderReference);

    Order save(Order order);

    Optional<Order> findByOrderId(MerchantId merchantId, OrderId orderId);

    /**
     * The same read, holding a row lock until the caller's transaction ends (SELECT ... FOR UPDATE).
     * <p>
     * It exists because a plain read is a check, not a lock: a caller that reads an order's status,
     * decides something on the strength of it and then writes is racing anything that moves the
     * order in between. Payment's create path is the caller -- it must not open a collection against
     * an order that is being cancelled underneath it -- and it reaches this through its own
     * {@code OrderLookup} port, never through this interface (ADR-008).
     * <p>
     * MUST be called inside a transaction; the lock is meaningless without one and the provider
     * will say so. Use {@link #findByOrderId} for every read that is only a read.
     */
    Optional<Order> findByOrderIdForUpdate(MerchantId merchantId, OrderId orderId);

    /**
     * One page of the merchant's orders, newest first, starting strictly after {@code cursor} and
     * ordered by {@code (createdAt, orderId)} descending. The tiebreak is part of the contract: an
     * implementation that orders by timestamp alone will skip or repeat rows that share one.
     */
    List<Order> findPage(MerchantId merchantId, OrderStatus status, OrderCursor cursor, int limit);
}
