package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.MerchantId;

public class MerchantNotFoundException extends RuntimeException {
    public MerchantNotFoundException(MerchantId merchantId) {
        super("Merchant not found: " + merchantId.value());
    }
}
