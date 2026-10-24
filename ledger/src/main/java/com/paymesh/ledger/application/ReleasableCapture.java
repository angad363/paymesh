package com.paymesh.ledger.application;

import java.time.Instant;

/**
 * One capture with no release journal yet, as raw columns.
 * <p>
 * Unparsed for the reason {@code ExpirableOrder} carries: building a {@code MerchantId} here would
 * put a throwing call outside the release pass's per-item boundary, where one unreadable row would
 * end the run instead of costing one payment (open item 2).
 */
public record ReleasableCapture(
    String merchantId, String paymentIntentId, String currency, Instant capturedAt
) {
}
