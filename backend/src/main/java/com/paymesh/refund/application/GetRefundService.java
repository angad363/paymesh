package com.paymesh.refund.application;

import com.paymesh.refund.domain.Refund;
import com.paymesh.refund.domain.RefundId;
import com.paymesh.shared.tenant.MerchantId;

/**
 * One refund, scoped to its merchant.
 * <p>
 * The merchant is an argument rather than something the repository infers, so there is no way to
 * call this without saying whose refund is wanted -- and the query is scoped, not filtered, so
 * another tenant's refund is simply not found.
 */
public final class GetRefundService {

    private final RefundRepository refunds;

    public GetRefundService(RefundRepository refunds) {
        this.refunds = refunds;
    }

    public Refund getById(MerchantId merchantId, RefundId refundId) {
        return refunds.findByRefundId(merchantId, refundId)
            .orElseThrow(() -> new RefundNotFoundException(refundId.value()));
    }
}
