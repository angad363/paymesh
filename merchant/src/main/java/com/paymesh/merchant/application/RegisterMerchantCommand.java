package com.paymesh.merchant.application;

public record RegisterMerchantCommand(
    String businessName,
    String email,
    String country,
    String defaultCurrency
) {
}
