package com.paymesh.merchant.domain;

import com.paymesh.shared.tenant.MerchantId;

/** A merchant status transition that the state machine does not permit. */
public final class MerchantStatusNotChangeableException extends IllegalStateException {

    private final MerchantId merchantId;
    private final MerchantStatus actual;
    private final MerchantStatus requested;

    public MerchantStatusNotChangeableException(
        MerchantId merchantId,
        MerchantStatus actual,
        MerchantStatus requested
    ) {
        super(
            "Merchant " + merchantId.value() + " is " + actual + " and cannot become " + requested
        );

        this.merchantId = merchantId;
        this.actual = actual;
        this.requested = requested;
    }

    public MerchantId merchantId() {
        return merchantId;
    }

    public MerchantStatus actual() {
        return actual;
    }

    public MerchantStatus requested() {
        return requested;
    }
}
