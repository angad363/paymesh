package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
import com.paymesh.merchant.domain.MerchantStatus;

/**
 * Translates between the domain aggregate and the persistence row, in both directions.
 * Explicit and field-by-field so a schema change surfaces as a compile error, not as lost data.
 */
public final class MerchantJpaMapper {

    private MerchantJpaMapper() {
    }

    public static MerchantJpaEntity toEntity(Merchant merchant) {
        return new MerchantJpaEntity(
            merchant.merchantId().value(),
            merchant.businessName(),
            merchant.email(),
            merchant.country(),
            merchant.defaultCurrency(),
            merchant.status().name(),
            merchant.createdAt(),
            merchant.updatedAt()
        );
    }

    public static Merchant toDomain(MerchantJpaEntity entity) {
        return Merchant.reconstitute(
            MerchantId.from(entity.merchantId()),
            entity.businessName(),
            entity.email(),
            entity.country(),
            entity.defaultCurrency(),
            MerchantStatus.valueOf(entity.status()),
            entity.createdAt(),
            entity.updatedAt()
        );
    }
}
