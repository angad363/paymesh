package com.paymesh.payment.api;

import com.paymesh.payment.domain.PaymentIntent;

import java.time.Instant;
import java.util.Map;

/**
 * The external shape of a payment intent.
 * <p>
 * The row's optimistic-lock version is deliberately not exposed: it is how the database protects
 * itself from concurrent writers, not a fact about the merchant's commerce.
 * <p>
 * There is no {@code clientSecret} (SDD 12.3) and no {@code allowedPaymentMethods}. The first is a
 * credential nothing in PayMesh can verify; the second has no provider to constrain. Both belong to
 * a checkout capability that does not exist.
 */
public record PaymentIntentResponse(
    String id,
    String merchantId,
    String orderId,
    String customerId,
    long amountMinor,
    String currency,
    String captureMethod,
    String status,
    long capturedAmountMinor,
    long refundedAmountMinor,
    String failureCode,
    String failureMessage,
    String description,
    Map<String, String> metadata,
    String cancellationReason,
    Instant cancelledAt,
    Instant createdAt,
    Instant updatedAt
) {
    public static PaymentIntentResponse from(PaymentIntent intent) {
        return new PaymentIntentResponse(
            intent.paymentIntentId().value(),
            intent.merchantId().value(),
            intent.orderId(),
            intent.customerId(),
            intent.amountMinor(),
            intent.currency(),
            intent.captureMethod().name(),
            intent.status().name(),
            intent.capturedAmountMinor(),
            intent.refundedAmountMinor(),
            intent.failureCode(),
            intent.failureMessage(),
            intent.description(),
            intent.metadata(),
            intent.cancellationReason(),
            intent.cancelledAt(),
            intent.createdAt(),
            intent.updatedAt()
        );
    }
}
