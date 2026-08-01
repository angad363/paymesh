package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.domain.PaymentAttempt;
import com.paymesh.payment.domain.PaymentAttemptId;
import com.paymesh.payment.domain.PaymentAttemptStatus;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Map;

/**
 * Translates between the attempt aggregate and its persistence row, in both directions.
 * Explicit and field-by-field so a schema change surfaces as a compile error, not as lost data.
 * <p>
 * It arrives with the callback PR because that is the first one that reads an attempt back. Confirm
 * only ever wrote one, so it built the entity inline; a round trip needs both directions in one
 * place, or the columns the write knows about and the columns the read knows about drift.
 */
public final class PaymentAttemptJpaMapper {

    private PaymentAttemptJpaMapper() {
    }

    public static PaymentAttemptJpaEntity toEntity(PaymentAttempt attempt) {
        return new PaymentAttemptJpaEntity(
            attempt.paymentAttemptId().value(),
            attempt.merchantId().value(),
            attempt.paymentIntentId().value(),
            attempt.attemptNumber(),
            attempt.provider(),
            attempt.providerReference(),
            attempt.status().name(),
            attempt.amountMinor(),
            attempt.currency(),
            attempt.failureCode(),
            attempt.failureMessage(),
            attempt.lastProviderEventAt(),
            // Absent request details are stored as SQL NULL rather than an empty JSON object, so
            // "the merchant sent none" and "the merchant sent {}" do not become two spellings of the
            // same thing in the column.
            attempt.requestPayload().isEmpty() ? null : attempt.requestPayload(),
            attempt.responsePayload(),
            attempt.version(),
            attempt.createdAt(),
            attempt.updatedAt()
        );
    }

    public static PaymentAttempt toDomain(PaymentAttemptJpaEntity entity) {
        return PaymentAttempt.reconstitute(
            PaymentAttemptId.from(entity.paymentAttemptId()),
            MerchantId.from(entity.merchantId()),
            PaymentIntentId.from(entity.paymentIntentId()),
            entity.attemptNumber(),
            entity.provider(),
            entity.providerReference(),
            PaymentAttemptStatus.valueOf(entity.status()),
            entity.amountMinor(),
            entity.currency(),
            entity.failureCode(),
            entity.failureMessage(),
            entity.lastProviderEventAt(),
            entity.requestPayload() == null ? Map.of() : entity.requestPayload(),
            entity.responsePayload(),
            entity.version(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
