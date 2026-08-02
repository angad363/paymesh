package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.MerchantStatusChange;

public interface MerchantStatusHistoryRepository {

    void append(MerchantStatusChange change);
}
