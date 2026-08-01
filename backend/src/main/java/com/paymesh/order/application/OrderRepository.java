package com.paymesh.order.application;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
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

    /**
     * THE ONE READ IN THIS PORT THAT DOES NOT TAKE A MERCHANT, AND IT IS NAMED SO THAT NOBODY
     * REACHES FOR IT BY ACCIDENT.
     * <p>
     * The expiry sweeper runs on a timer. It has no caller, no token and therefore no tenant, and
     * the work it does is spread across every merchant on the platform -- so the caller-scoped reads
     * above cannot serve it, and adding a merchant argument would mean the scheduler inventing one.
     * A tenant taken from anywhere but a verified token is exactly what ADR-007 exists to prevent,
     * and there is no token here to take it from.
     * <p>
     * <b>Tenant-agnostic is not tenant-unsafe.</b> Each returned order carries its own merchant, and
     * the sweeper scopes every write that follows -- the locking re-read, the live-intent question,
     * the history row, the event -- by that merchant. Nothing is written by this method and nothing
     * is decided from it: it is a candidate list, re-checked under a row lock before anything moves.
     * <p>
     * <b>Not for any other caller.</b> Every merchant-facing path uses one of the reads above, where
     * the merchant argument is the authorization.
     *
     * @param now   the sweep instant; orders whose {@code expiresAt} is at or before it are returned
     * @param limit the batch size, so one sweep cannot load an unbounded backlog into memory
     * @return PENDING orders past their expiry, oldest deadline first, across all merchants
     */
    List<Order> findExpirable(Instant now, int limit);
}
