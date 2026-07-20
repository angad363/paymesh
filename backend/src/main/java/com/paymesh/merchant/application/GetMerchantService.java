package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;

public final class GetMerchantService {

    private final MerchantRepository merchantRepository;

    public GetMerchantService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    public Merchant getById(MerchantId merchantId) {
        if(merchantId == null) {
            throw  new IllegalArgumentException("Merchant ID cannot be null");
        }

        return merchantRepository
            .findByMerchantId(merchantId)
            .orElseThrow(
                () -> new MerchantNotFoundException(merchantId)
            );
    }
}
