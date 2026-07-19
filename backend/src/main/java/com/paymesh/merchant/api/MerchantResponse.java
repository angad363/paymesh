package com.paymesh.merchant.api;

import com.paymesh.merchant.domain.Merchant;

import java.time.Instant;

public record MerchantResponse(
    String id,
    String businessName,
    String email,
    String country,
    String defaultCurrency,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
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
}
