package com.paymesh.customer.application;

import com.paymesh.customer.domain.CustomerStatusChange;

public interface CustomerStatusHistoryRepository {

    void append(CustomerStatusChange change);
}
