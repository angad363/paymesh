package com.paymesh.order.api;

import com.paymesh.order.domain.Order;

import java.time.Instant;
import java.util.Map;

/**
 * The external shape of an order.
 * <p>
 * The row's optimistic-lock version is deliberately not exposed: it is how the database protects
 * itself from concurrent writers, not a fact about the merchant's commerce, and publishing it would
 * invite clients to depend on it.
 */
public record OrderResponse(
    String id,
    String merchantId,
    String customerId,
    String merchantOrderReference,
    long amountMinor,
    String currency,
    long amountPaidMinor,
    String status,
    String description,
    Map<String, String> metadata,
    Instant expiresAt,
    String cancellationReason,
    Instant cancelledAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
            order.orderId().value(),
            order.merchantId().value(),
            order.customerId(),
            order.merchantOrderReference(),
            order.amountMinor(),
            order.currency(),
            order.amountPaidMinor(),
            order.status().name(),
            order.description(),
            order.metadata(),
            order.expiresAt(),
            order.cancellationReason(),
            order.cancelledAt(),
            order.createdAt(),
            order.updatedAt()
        );
    }
}
