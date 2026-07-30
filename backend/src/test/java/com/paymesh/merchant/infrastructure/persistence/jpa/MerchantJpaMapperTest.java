package com.paymesh.merchant.infrastructure.persistence.jpa;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantJpaMapperTest {

    private static final MerchantId MERCHANT_ID = MerchantId.generate();
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T10:15:30Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-02-02T08:00:00Z");

    @Test
    void mapsEveryDomainFieldOntoTheEntity() {
        MerchantJpaEntity entity = MerchantJpaMapper.toEntity(activeMerchant());

        assertThat(entity.merchantId()).isEqualTo(MERCHANT_ID.value());
        assertThat(entity.businessName()).isEqualTo("Acme Ltd");
        assertThat(entity.email()).isEqualTo("owner@acme.test");
        assertThat(entity.country()).isEqualTo("US");
        assertThat(entity.defaultCurrency()).isEqualTo("USD");
        assertThat(entity.status()).isEqualTo("ACTIVE");
        assertThat(entity.createdAt()).isEqualTo(CREATED_AT);
        assertThat(entity.updatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void roundTripPreservesStatusAndBothTimestamps() {
        Merchant original = activeMerchant();

        Merchant roundTripped = MerchantJpaMapper.toDomain(MerchantJpaMapper.toEntity(original));

        assertThat(roundTripped.merchantId()).isEqualTo(original.merchantId());
        assertThat(roundTripped.businessName()).isEqualTo(original.businessName());
        assertThat(roundTripped.email()).isEqualTo(original.email());
        assertThat(roundTripped.country()).isEqualTo(original.country());
        assertThat(roundTripped.defaultCurrency()).isEqualTo(original.defaultCurrency());
        assertThat(roundTripped.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(roundTripped.createdAt()).isEqualTo(CREATED_AT);
        assertThat(roundTripped.updatedAt()).isEqualTo(UPDATED_AT);
    }

    private static Merchant activeMerchant() {
        return Merchant.reconstitute(
            MERCHANT_ID,
            "Acme Ltd",
            "owner@acme.test",
            "US",
            "USD",
            MerchantStatus.ACTIVE,
            CREATED_AT,
            UPDATED_AT
        );
    }
}
