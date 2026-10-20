package com.paymesh.order.application;

import com.paymesh.shared.tenant.MerchantId;

import java.time.Instant;
import java.util.Map;

/**
 * The input to "create an order".
 * <p>
 * merchantId comes from the verified access token, never from the request body -- an API record
 * cannot carry it, so it cannot be spoofed.
 */
public record CreateOrderCommand(
    MerchantId merchantId,
    String customerId,
    String merchantOrderReference,
    long amountMinor,
    String currency,
    String description,
    Map<String, String> metadata,
    Instant expiresAt
) {
}
