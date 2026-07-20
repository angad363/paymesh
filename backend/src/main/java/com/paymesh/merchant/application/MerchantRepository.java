package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;

import java.util.Optional;

public interface MerchantRepository {

    boolean existsByEmail(String normalizedEmail);

    Merchant save(Merchant merchant);

    Optional<Merchant> findByMerchantId(MerchantId merchantId);
}
