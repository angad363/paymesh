package com.paymesh.refund.infrastructure.persistence.jpa;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.refund.domain.RefundStatus;
import com.paymesh.shared.tenant.MerchantId;

/** Domain <-> persistence, both directions, in one place (ADR-004). */
final class RefundJpaMapper {

    private RefundJpaMapper() {
    }

    static RefundJpaEntity toEntity(Refund refund) {
        return new RefundJpaEntity(
            refund.refundId().value(),
            refund.merchantId().value(),
            refund.paymentIntentId(),
            refund.amountMinor(),
            refund.currency(),
            // The enum NAME, never the ordinal. An ordinal makes the column meaningless to read and
            // reorders silently when a constant is inserted; ck_refunds_status also spells the names.
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

    static Refund toDomain(RefundJpaEntity entity) {
        return new Refund(
            RefundId.from(entity.refundId()),
            MerchantId.from(entity.merchantId()),
            entity.paymentIntentId(),
            entity.amountMinor(),
            entity.currency(),
            RefundStatus.valueOf(entity.status()),
            entity.merchantReference(),
            entity.reason(),
            entity.providerReference(),
            entity.failureCode(),
            entity.failureMessage(),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
