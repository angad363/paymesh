package com.paymesh.payment.infrastructure.persistence.jpa;

import com.paymesh.payment.domain.CaptureMethod;
import com.paymesh.payment.domain.PaymentIntent;
import com.paymesh.payment.domain.PaymentIntentId;
import com.paymesh.payment.domain.PaymentIntentStatus;
import com.paymesh.payment.domain.PaymentMethodType;
import com.paymesh.shared.tenant.MerchantId;

import java.util.Map;

/**
 * Translates between the domain aggregate and the persistence row, in both directions.
 * Explicit and field-by-field so a schema change surfaces as a compile error, not as lost data.
 */
public final class PaymentIntentJpaMapper {

    private PaymentIntentJpaMapper() {
    }

    public static PaymentIntentJpaEntity toEntity(PaymentIntent intent) {
        return new PaymentIntentJpaEntity(
            intent.paymentIntentId().value(),
            intent.merchantId().value(),
            intent.orderId(),
            intent.customerId(),
            intent.amountMinor(),
            intent.currency(),
            intent.captureMethod().name(),
            // Null until a method is attached, which the CHECK tolerates in exactly two states.
            intent.paymentMethodType() == null ? null : intent.paymentMethodType().name(),
            intent.status().name(),
            intent.capturedAmountMinor(),
            intent.refundedAmountMinor(),
            intent.failureCode(),
            intent.failureMessage(),
            intent.cancellationReason(),
            intent.cancelledAt(),
            intent.description(),
            // Absent metadata is stored as SQL NULL rather than an empty JSON object, so "the
            // merchant sent none" and "the merchant sent {}" do not become two spellings of the
            // same thing in the column.
            intent.metadata().isEmpty() ? null : intent.metadata(),
            intent.version(),
            intent.createdAt(),
            intent.updatedAt()
        );
    }

    public static PaymentIntent toDomain(PaymentIntentJpaEntity entity) {
        return PaymentIntent.reconstitute(
            PaymentIntentId.from(entity.paymentIntentId()),
            MerchantId.from(entity.merchantId()),
            entity.orderId(),
            entity.customerId(),
            entity.amountMinor(),
            entity.currency(),
            CaptureMethod.valueOf(entity.captureMethod()),
            entity.paymentMethodType() == null
                ? null
                : PaymentMethodType.valueOf(entity.paymentMethodType()),
            PaymentIntentStatus.valueOf(entity.status()),
            entity.capturedAmountMinor(),
            entity.refundedAmountMinor(),
            entity.failureCode(),
            entity.failureMessage(),
            entity.cancellationReason(),
            entity.cancelledAt(),
            entity.description(),
            entity.metadata() == null ? Map.of() : entity.metadata(),
            entity.version(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
