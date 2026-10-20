package com.paymesh.refund.api;

import com.paymesh.refund.domain.Refund;

import java.time.Instant;

/**
 * @param id the {@code ref_} identifier. Named {@code id} rather than {@code refundId} to match the
 *     shape every other resource here returns -- the API conventions doc prescribes the latter, and
 *     the code has consistently used the former; CLAUDE.md says match the code.
 * @param amountMinor integer minor units, no decimals anywhere
 */
public record RefundResponse(
    String id,
    String merchantId,
    String paymentIntentId,
    long amountMinor,
    String currency,
    String status,
    String merchantReference,
    String reason,
    String providerReference,
    String failureCode,
    String failureMessage,
    Instant createdAt,
    Instant updatedAt
) {

    public static RefundResponse from(Refund refund) {
        return new RefundResponse(
            refund.refundId().value(),
            refund.merchantId().value(),
            refund.paymentIntentId(),
            refund.amountMinor(),
            refund.currency(),
            refund.status().name(),
            refund.merchantReference(),
            refund.reason(),
            refund.providerReference(),
            refund.failureCode(),
            refund.failureMessage(),
            refund.createdAt(),
            refund.updatedAt()
        );
    }
}
