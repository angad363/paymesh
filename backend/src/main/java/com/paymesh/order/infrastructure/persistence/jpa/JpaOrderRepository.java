package com.paymesh.order.infrastructure.persistence.jpa;

import com.paymesh.order.application.OrderCursor;
import com.paymesh.order.application.OrderReferenceAlreadyExistsException;
import com.paymesh.order.application.OrderRepository;
import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL-backed implementation of the application's OrderRepository port.
 * Everything JPA stays on this side of the interface; the services see only domain types.
 */
public final class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository orders;

    public JpaOrderRepository(SpringDataOrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public boolean existsByMerchantOrderReference(MerchantId merchantId, String merchantOrderReference) {
        return orders.existsByMerchantIdAndMerchantOrderReference(
            merchantId.value(), merchantOrderReference
        );
    }

    @Override
    public Order save(Order order) {
        try {
            OrderJpaEntity saved = orders.saveAndFlush(OrderJpaMapper.toEntity(order));
            return OrderJpaMapper.toDomain(saved);
        } catch (DataIntegrityViolationException exception) {
            // The service's existsByMerchantOrderReference is a check, not a lock: two concurrent
            // creates can both pass it. uq_orders_merchant_ref is the real guard, and the loser of
            // the race must still get a 409 rather than a 500.
            //
            // Narrowed to the case where that constraint can actually fire. With a null reference
            // the unique index cannot be violated, so a violation here is something else entirely
            // (an order naming another merchant's customer and tripping fk_orders_customer, say)
            // and must not be disguised as a duplicate-reference conflict.
            if (order.merchantOrderReference() == null) {
                throw exception;
            }

            throw new OrderReferenceAlreadyExistsException(order.merchantOrderReference());
        }
    }

    @Override
    public Optional<Order> findByOrderId(MerchantId merchantId, OrderId orderId) {
        return orders.findByMerchantIdAndOrderId(merchantId.value(), orderId.value())
            .map(OrderJpaMapper::toDomain);
    }

    @Override
    public Optional<Order> findByOrderIdForUpdate(MerchantId merchantId, OrderId orderId) {
        return orders.findForUpdateByMerchantIdAndOrderId(merchantId.value(), orderId.value())
            .map(OrderJpaMapper::toDomain);
    }

    @Override
    public List<Order> findPage(
        MerchantId merchantId,
        OrderStatus status,
        OrderCursor cursor,
        int limit
    ) {
        List<OrderJpaEntity> page = status == null
            ? orders.findPage(
                merchantId.value(), cursor.createdAt(), cursor.orderId(), Limit.of(limit)
            )
            : orders.findPageByStatus(
                merchantId.value(), status.name(), cursor.createdAt(), cursor.orderId(),
                Limit.of(limit)
            );

        return page.stream().map(OrderJpaMapper::toDomain).toList();
    }
}
