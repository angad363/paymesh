package com.paymesh.refund.application;

import com.paymesh.refund.domain.RefundStateChange;

public interface RefundStateHistoryRepository {

    void append(RefundStateChange change);
}
