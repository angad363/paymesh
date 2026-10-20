package com.paymesh.order.infrastructure.persistence.jpa;

import com.paymesh.order.domain.Order;
import com.paymesh.order.domain.OrderId;
import com.paymesh.order.domain.OrderStatus;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Map;

/**
 * Translates between the domain aggregate and the persistence row, in both directions.
 * Explicit and field-by-field so a schema change surfaces as a compile error, not as lost data.
 */
public final class OrderJpaMapper {

    private OrderJpaMapper() {
    }

    public static OrderJpaEntity toEntity(Order order) {
        return new OrderJpaEntity(
            order.orderId().value(),
            order.merchantId().value(),
            order.customerId(),
            order.merchantOrderReference(),
            order.amountMinor(),
            order.currency(),
            order.amountPaidMinor(),
            order.status().name(),
            order.description(),
            // Absent metadata is stored as SQL NULL rather than an empty JSON object, so "the
            // merchant sent none" and "the merchant sent {}" do not become two spellings of the
            // same thing in the column.
            order.metadata().isEmpty() ? null : order.metadata(),
            order.expiresAt(),
            order.cancellationReason(),
            order.cancelledAt(),
            order.version(),
            order.createdAt(),
            order.updatedAt()
        );
    }

    public static Order toDomain(OrderJpaEntity entity) {
        return Order.reconstitute(
            OrderId.from(entity.orderId()),
            MerchantId.from(entity.merchantId()),
            entity.customerId(),
            entity.merchantOrderReference(),
            entity.amountMinor(),
            entity.currency(),
            entity.amountPaidMinor(),
            OrderStatus.valueOf(entity.status()),
            entity.description(),
            entity.metadata() == null ? Map.of() : entity.metadata(),
            entity.expiresAt(),
            entity.cancellationReason(),
            entity.cancelledAt(),
            entity.version(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
