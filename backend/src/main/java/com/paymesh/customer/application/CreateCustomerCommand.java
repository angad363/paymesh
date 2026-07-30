package com.paymesh.customer.application;

import com.paymesh.merchant.domain.MerchantId;

/**
 * The merchantId is a field of the command, not of any future request body: it comes from the
 * authenticated principal at the API boundary, never from the caller's JSON.
 */
public record CreateCustomerCommand(
    MerchantId merchantId,
    String merchantReference,
    String email,
    String name,
    String phone
) {
}
