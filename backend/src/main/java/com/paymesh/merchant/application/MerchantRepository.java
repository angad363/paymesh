package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;

public interface MerchantRepository {

    boolean existsByEmail(String normalizedEmail);

    Merchant save(Merchant merchant);
}
