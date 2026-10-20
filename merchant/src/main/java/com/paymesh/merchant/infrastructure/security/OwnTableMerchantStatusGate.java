package com.paymesh.merchant.infrastructure.security;

import com.paymesh.merchant.application.MerchantRepository;
import com.paymesh.shared.tenant.MerchantId;
import com.paymesh.shared.tenant.MerchantStatusGate;
import com.paymesh.shared.tenant.MerchantTransactability;

/**
 * The merchant service owns {@code merchants}, so it answers ADR-021's question from its own table
 * (always current, no projection lag): ACTIVE is allowed, anything else or absent is denied.
 */
public final class OwnTableMerchantStatusGate implements MerchantStatusGate {

    private final MerchantRepository merchants;

    public OwnTableMerchantStatusGate(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    public MerchantTransactability verdict(MerchantId merchantId) {
        return merchants.findByMerchantId(merchantId)
            .filter(com.paymesh.merchant.domain.Merchant::canTransact)
            .map(m -> MerchantTransactability.ALLOWED)
            .orElse(MerchantTransactability.DENIED);
    }
}
